package com.jujin.freeway.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal conditional expression evaluator (zero external dependencies, recursive-descent parsing)
 *
 * <p>Freeway-specific — no counterpart in solon-flow (which delegates
 * expression evaluation to liquor-eval/Snel via the Evaluation SPI). This
 * recursive-descent compiler is an independent implementation.
 *
 * <p>Supported syntax:
 * <pre>
 * expression  → or_expr
 * or_expr     → and_expr ("||" and_expr)*
 * and_expr    → cmp_expr ("&&" cmp_expr)*
 * cmp_expr    → add_expr (("=="|"!="|">"|"<"|">="|"<=") add_expr)?
 * add_expr    → mul_expr (("+"|"-") mul_expr)*
 * mul_expr    → unary_expr (("*"|"/"|"%") unary_expr)*
 * unary_expr  → ("!" | "-" | "+") unary_expr | primary
 * primary     → NUMBER | STRING | "true" | "false" | "null" | IDENT | "(" expression ")"
 * IDENT       → [a-zA-Z_][a-zA-Z0-9_.]*  (supports data.name path access)
 * </pre>
 *
 * <p><b>Numeric precision:</b> arithmetic ({@code + - * / %}) routes through
 * {@code double}, so {@code long} values at/above 2^53 and {@code BigDecimal}
 * context values lose exactness. This is deliberate for a gateway-condition
 * evaluator — comparisons use the precision-preserving
 * {@link #compareNumbers} path, and conditions like
 * {@code price * qty > 100} never need exact wide arithmetic. Do not route
 * money math through expressions; compute it in a task and compare the
 * result.
 *</p>
 *
 * Expressions are compiled to an AST (abstract syntax tree) on first use; later the same
 * expression is evaluated directly, avoiding repeated parsing.
 *
 */
public final class ExprEvaluator {

    private static final int CACHE_MAX = 512;

    /**
     * accessOrder=true makes every get() a structural write (afterNodeAccess
     * relinks the internal list), so plain read-locking is unsafe: concurrent
     * gets can corrupt the linkage. synchronizedMap serializes get AND put
     * (and thus the access-order relink) atomically; the double-checked
     * lookup below keeps compilation out of the common path.
     */
    private static final Map<String, AstNode> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(CACHE_MAX, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AstNode> eldest) {
                return size() > CACHE_MAX;
            }
        });

    private ExprEvaluator() {}

    /**
     * Evaluates a condition expression, returning a boolean
     */
    public static boolean evalCondition(String expr, Map<String, Object> context) {
        if (expr == null || expr.isBlank()) return true;
        String key = expr.trim();
        AstNode node = CACHE.get(key);
        if (node == null) {
            synchronized (CACHE) {
                node = CACHE.get(key);
                if (node == null) {
                    node = Compiler.compile(key);
                    CACHE.put(key, node);
                }
            }
        }
        Object val = node.eval(context);
        // Top-level expressions must use the same truthiness as operators:
        // a bare "flag" holding "false" would otherwise be truthy while
        // "flag && true" is falsy.
        return toBool(val);
    }

    // ======================== AST node types ========================

    private interface AstNode {
        Object eval(Map<String, Object> ctx);
    }

    private record Literal(Object value) implements AstNode {
        @Override public Object eval(Map<String, Object> ctx) { return value; }
    }

    private record Ident(String name, List<String> parts) implements AstNode {
        private Ident {
            // List.copyOf: the compiled AST is shared via the expression
            // cache, so the split must not be externally mutable — a record
            // array component would expose the internal array through its
            // accessor and allow cache poisoning.
            parts = parts == null ? List.of(name.split("\\.")) : List.copyOf(parts);
        }
        @Override public Object eval(Map<String, Object> ctx) {
            Object cur = ctx;
            for (String p : parts) {
                if (cur instanceof Map<?, ?> m) {
                    cur = m.get(p);
                } else if (cur instanceof List<?> list) {
                    try {
                        cur = list.get(Integer.parseInt(p));
                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        return null;
                    }
                } else {
                    return null;
                }
                if (cur == null) return null;
            }
            return cur;
        }
    }

    private record BinaryOp(AstNode left, String op, AstNode right) implements AstNode {
        @Override public Object eval(Map<String, Object> ctx) {
            Object l = left.eval(ctx);
            return switch (op) {
                // Short-circuit: the right operand is only evaluated when it
                // can affect the result — `false && (x - 1)` must yield false
                // without evaluating (x - 1), matching conventional boolean
                // semantics. All other operators stay eager.
                case "||", "or" -> toBool(l) || toBool(right.eval(ctx));
                case "&&", "and" -> toBool(l) && toBool(right.eval(ctx));
                case "==" -> eq(l, right.eval(ctx));
                case "!=" -> !eq(l, right.eval(ctx));
                case ">=" -> cmp(l, right.eval(ctx)) >= 0;
                case "<=" -> cmp(l, right.eval(ctx)) <= 0;
                case ">" -> cmp(l, right.eval(ctx)) > 0;
                case "<" -> cmp(l, right.eval(ctx)) < 0;
                case "+" -> add(l, right.eval(ctx));
                case "-" -> sub(l, right.eval(ctx));
                case "*" -> mul(l, right.eval(ctx));
                case "/" -> div(l, right.eval(ctx));
                case "%" -> mod(l, right.eval(ctx));
                default -> throw new FlowException("Unknown operator: " + op);
            };
        }
    }

    private record UnaryOp(String op, AstNode child) implements AstNode {
        @Override public Object eval(Map<String, Object> ctx) {
            return switch (op) {
                case "!", "not" -> !toBool(child.eval(ctx));
                case "-" -> negate(child.eval(ctx));
                case "+" -> child.eval(ctx);
                default -> throw new FlowException("Unknown unary operator: " + op);
            };
        }
    }

    // ======================== Compilation (parse → AST, runs once) ========================

    private static final class Compiler {
        private static final int MAX_NESTING_DEPTH = 64;
        /**
         * Upper bound on expression terms. Flat operator chains (a && a && …)
         * build left-leaning BinaryOp trees iteratively — the nesting guard
         * never fires for them — but eval recurses once per term, so a very
         * long chain would overflow the stack at evaluation time (and poison
         * the AST cache). Every term passes through primary(), so counting
         * there covers all shapes.
         */
        private static final int MAX_TERMS = 2048;
        private final String expr;
        private int pos;
        private final int len;
        private int depth;
        private int terms;

        static AstNode compile(String expr) {
            Compiler c = new Compiler(expr);
            AstNode node = c.orExpr();
            c.skipSpaces();
            if (c.pos < c.len) {
                throw new FlowException("Unexpected token at position " + c.pos + ": '" + c.expr.charAt(c.pos) + "' in '" + c.expr + "'");
            }
            return node;
        }

        private Compiler(String expr) {
            this.expr = expr;
            this.pos = 0;
            this.len = expr.length();
        }

        private static String shorten(String s) {
            return s.length() > 80 ? s.substring(0, 80) + "…" : s;
        }

        private AstNode orExpr() {
            AstNode left = andExpr();
            skipSpaces();
            while (match("||") || matchIgnoreCase("or")) {
                AstNode right = andExpr();
                left = new BinaryOp(left, "||", right);
                skipSpaces();
            }
            return left;
        }

        private AstNode andExpr() {
            AstNode left = cmpExpr();
            skipSpaces();
            while (match("&&") || matchIgnoreCase("and")) {
                AstNode right = cmpExpr();
                left = new BinaryOp(left, "&&", right);
                skipSpaces();
            }
            return left;
        }

        private AstNode cmpExpr() {
            AstNode left = addExpr();
            skipSpaces();
            if (match("==")) return new BinaryOp(left, "==", addExpr());
            if (match("!=")) return new BinaryOp(left, "!=", addExpr());
            if (match(">=")) return new BinaryOp(left, ">=", addExpr());
            if (match("<=")) return new BinaryOp(left, "<=", addExpr());
            if (match(">"))  return new BinaryOp(left, ">", addExpr());
            if (match("<"))  return new BinaryOp(left, "<", addExpr());
            return left;
        }

        private AstNode addExpr() {
            AstNode left = mulExpr();
            skipSpaces();
            while (match("+") || match("-")) {
                String op = expr.charAt(pos - 1) == '-' ? "-" : "+";
                AstNode right = mulExpr();
                left = new BinaryOp(left, op, right);
                skipSpaces();
            }
            return left;
        }

        private AstNode mulExpr() {
            AstNode left = unaryExpr();
            skipSpaces();
            while (match("*") || match("/") || match("%")) {
                String op = expr.charAt(pos - 1) == '*' ? "*"
                    : expr.charAt(pos - 1) == '/' ? "/" : "%";
                AstNode right = unaryExpr();
                left = new BinaryOp(left, op, right);
                skipSpaces();
            }
            return left;
        }

        private AstNode unaryExpr() {
            skipSpaces();
            String op = null;
            if (match("!")) op = "!";
            else if (match("-")) op = "-";
            else if (match("+")) op = "+";
            if (op != null) {
                // Unary chains recurse without passing through primary(),
                // so count them against the same nesting budget.
                if (++depth > MAX_NESTING_DEPTH) {
                    throw new FlowException(
                        "Expression nested too deeply (max " + MAX_NESTING_DEPTH
                            + ") in '" + expr + "'"
                    );
                }
                try {
                    return new UnaryOp(op, unaryExpr());
                } finally {
                    depth--;
                }
            }
            return primary();
        }

        private AstNode primary() {
            if (++terms > MAX_TERMS) {
                throw new FlowException(
                    "Expression too complex (more than " + MAX_TERMS
                        + " terms) in '" + shorten(expr) + "'"
                );
            }
            // Depth guard: parenthesized and unary (`!`/`not`/`-`/`+`)
            // expressions recurse, and a pathological input (e.g. thousands
            // of nested parens) would otherwise blow the JVM stack with a raw
            // StackOverflowError during compilation. All recursive paths
            // funnel through primary(), so counting here covers them all.
            if (++depth > MAX_NESTING_DEPTH) {
                throw new FlowException(
                    "Expression nested too deeply (max " + MAX_NESTING_DEPTH
                        + ") in '" + expr + "'"
                );
            }
            try {
                skipSpaces();
                if (pos >= len) throw new FlowException("Unexpected end of expression: '" + expr + "'");
                char c = expr.charAt(pos);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) return new Literal(number());
                if (c == '\'' || c == '"') return new Literal(string());
                if (c == '(') { pos++; var val = orExpr(); skipSpaces(); require(')'); return val; }
                if (isIdentStart(c)) {
                    String id = ident();
                    return switch (id) {
                        case "true" -> new Literal(true);
                        case "false" -> new Literal(false);
                        case "null" -> new Literal(null);
                        case "not" -> new UnaryOp("not", unaryExpr());
                        default -> new Ident(id, null);
                    };
                }
                throw new FlowException("Unexpected character '" + c + "' at position " + pos + " in '" + expr + "'");
            } finally {
                depth--;
            }
        }

        private void require(char expected) {
            skipSpaces();
            if (pos < len && expr.charAt(pos) == expected) { pos++; }
            else throw new FlowException("Missing '" + expected + "' at position " + pos + " in '" + expr + "'");
        }

        private Number number() {
            int start = pos;
            if (pos < len && (expr.charAt(pos) == '-' || expr.charAt(pos) == '+')) pos++;
            boolean isDecimal = false;
            while (pos < len) {
                char c = expr.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' && !isDecimal) { isDecimal = true; pos++; }
                else break;
            }
            String numStr = expr.substring(start, pos);
            try {
                if (isDecimal) return Double.parseDouble(numStr);
                long v = Long.parseLong(numStr);
                return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? (int) v : v;
            } catch (NumberFormatException e) {
                throw new FlowException("Invalid number: '" + numStr + "' in '" + expr + "'", e);
            }
        }

        private String string() {
            char quote = expr.charAt(pos++);
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = expr.charAt(pos);
                if (c == '\\') {
                    pos++;
                    if (pos < len) sb.append(switch (expr.charAt(pos)) {
                        case 'n' -> '\n'; case 't' -> '\t'; case '\\' -> '\\';
                        case '"' -> '"'; case '\'' -> '\''; default -> expr.charAt(pos);
                    });
                    pos++;
                } else if (c == quote) { pos++; return sb.toString(); }
                else { sb.append(c); pos++; }
            }
            throw new FlowException("Unterminated string in '" + expr + "'");
        }

        private String ident() {
            int start = pos;
            while (pos < len && isIdentPart(expr.charAt(pos))) pos++;
            return expr.substring(start, pos);
        }

        private void skipSpaces() {
            while (pos < len && Character.isWhitespace(expr.charAt(pos))) pos++;
        }

        private boolean match(String token) {
            skipSpaces();
            if (expr.startsWith(token, pos)) {
                int end = pos + token.length();
                // an operator may immediately follow an identifier (e.g. "!active");
                // in that case the ident-part check is skipped
                if (end == len || !isIdentStart(token.charAt(0)) || !isIdentPart(expr.charAt(end))) {
                    pos = end; return true;
                }
            }
            return false;
        }

        private boolean matchIgnoreCase(String token) {
            skipSpaces();
            if (pos + token.length() <= len) {
                if (expr.substring(pos, pos + token.length()).equalsIgnoreCase(token)) {
                    int end = pos + token.length();
                    if (end == len || !isIdentPart(expr.charAt(end))) { pos = end; return true; }
                }
            }
            return false;
        }

        private static boolean isIdentStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
        }

        private static boolean isIdentPart(char c) {
            return isIdentStart(c) || (c >= '0' && c <= '9') || c == '.';
        }
    }

    // ======================== Utility methods ========================

    /**
     * Compares two numbers without losing long precision: the compiler
     * produces Integer/Long/Double, and context values may add Float. Routing
     * through doubleValue() collapses distinct longs at/above 2^53, silently
     * picking the wrong branch for large ids/timestamps — so integral types
     * compare pairwise first.
     */
    private static int compareNumbers(Number a, Number b) {
        if (a instanceof Long la) {
            if (b instanceof Long lb) return Long.compare(la, lb);
            if (b instanceof Integer ib) return Long.compare(la, ib.longValue());
        } else if (a instanceof Integer ia) {
            if (b instanceof Integer ib) return Integer.compare(ia, ib);
            if (b instanceof Long lb) return Long.compare(ia.longValue(), lb);
        }
        if (a instanceof Float fa && b instanceof Float fb) {
            return Float.compare(fa, fb);
        }
        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    /**
     * Tries to interpret a string as a number for mixed Number/String
     * comparisons ("score":"90" from JSON vs a numeric literal). Long first
     * (exact, no precision loss), then Double. Returns {@code null} for
     * non-numeric strings so callers can fall back to lexicographic ordering.
     */
    private static Number parseNumericString(String s) {
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {
            // fall through to double
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Mixed Number/String comparison: parses the string as a number and
     * compares numerically ("10" > 9). Returns {@code null} for non-numeric
     * strings so callers can fall back to their non-numeric paths. The
     * comparison is antisymmetric, so a caller comparing a string operand
     * against a number operand (string first) must negate the result.
     */
    private static Integer compareMixed(Number n, String s) {
        Number parsed = parseNumericString(s);
        if (parsed == null) return null;
        return compareNumbers(n, parsed);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number an && b instanceof Number bn)
            return compareNumbers(an, bn);
        // Mixed number/string: compare numerically when the string parses,
        // so "10" > 9 is true instead of lexicographic ("10" < "9"). Keeps
        // pure-string and pure-number paths untouched.
        if (a instanceof Number an && b instanceof String bs) {
            Integer r = compareMixed(an, bs);
            if (r != null) return r;
        } else if (a instanceof String as && b instanceof Number bn) {
            // compareMixed(bn, as) = compareNumbers(bn, parsed-as); the
            // original compares (parsed-as, bn) — negate to keep the sign.
            Integer r = compareMixed(bn, as);
            if (r != null) return -r;
        }
        if (a instanceof Comparable ca && b instanceof Comparable cb) {
            try { return ca.compareTo(b); } catch (Exception ignored) {}
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number an && b instanceof Number bn)
            return compareNumbers(an, bn) == 0;
        // Mixed number/string: numeric strings equal by value ("10" == 10,
        // "1.5" == 1.5), consistent with cmp and toBool. Non-numeric strings
        // fall through to plain equality, preserving current behavior.
        if (a instanceof Number an && b instanceof String bs) {
            Integer r = compareMixed(an, bs);
            if (r != null) return r == 0;
        }
        if (a instanceof String as && b instanceof Number bn) {
            Integer r = compareMixed(bn, as);
            if (r != null) return r == 0;
        }
        // Boolean strings compare by value: "false" == false is true,
        // matching truthiness (toBool) so equality and bare-flag routing agree.
        if (a instanceof Boolean ba && b instanceof String sb) return ba == toBool(sb);
        if (a instanceof String sa && b instanceof Boolean bb) return toBool(sa) == bb;
        return a.equals(b);
    }

    /**
     * Boolean coercion. JSON-derived values are interpreted by value:
     * "true"/"1" are truthy, "false"/"0" are falsy (previously any non-empty
     * string was truthy, so a context flag holding "false" routed the true
     * branch while {@code flag == false} compared equal — inverted and
     * inconsistent). Other non-empty strings stay truthy for compatibility.
     */
    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) {
            if (s.isEmpty()) return false;
            if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
            return true;
        }
        return true;
    }

    private static Object add(Object l, Object r) {
        if (l instanceof Number ln && r instanceof Number rn) return ln.doubleValue() + rn.doubleValue();
        return String.valueOf(l) + String.valueOf(r);
    }

    private static Object sub(Object l, Object r) {
        if (l instanceof Number ln && r instanceof Number rn) return ln.doubleValue() - rn.doubleValue();
        throw new FlowException("Cannot subtract non-numeric values: " + l + " - " + r);
    }

    private static Object mul(Object l, Object r) {
        if (l instanceof Number ln && r instanceof Number rn) return ln.doubleValue() * rn.doubleValue();
        throw new FlowException("Cannot multiply non-numeric values: " + l + " * " + r);
    }

    private static Object div(Object l, Object r) {
        if (l instanceof Number ln && r instanceof Number rn) {
            double d = rn.doubleValue();
            if (d == 0d) {
                // Division by zero must fail the condition loudly — a silent
                // Infinity would compare as greater than everything and route
                // branches in surprising ways.
                throw new FlowException("Division by zero: " + l + " / " + r);
            }
            return ln.doubleValue() / d;
        }
        throw new FlowException("Cannot divide non-numeric values: " + l + " / " + r);
    }

    private static Object mod(Object l, Object r) {
        if (l instanceof Number ln && r instanceof Number rn) {
            double d = rn.doubleValue();
            if (d == 0d) {
                throw new FlowException("Modulo by zero: " + l + " % " + r);
            }
            return ln.doubleValue() % d;
        }
        throw new FlowException("Cannot apply modulo to non-numeric values: " + l + " % " + r);
    }

    /**
     * Unary minus. Type-preserving for the boxed numerics the compiler
     * produces (Integer/Long/Double/Float), so {@code -5} stays an Integer
     * and {@code -9223372036854775807} stays an exact Long — the same shapes
     * the old signed-literal path produced. Non-numeric operands throw the
     * same clear error family as {@link #sub}.
     */
    private static Object negate(Object v) {
        if (v instanceof Integer i) return -i;
        if (v instanceof Long l) return -l;
        if (v instanceof Double d) return -d;
        if (v instanceof Float f) return -f;
        if (v instanceof Number n) return -n.doubleValue();
        throw new FlowException("Cannot negate non-numeric value: " + v);
    }
}
