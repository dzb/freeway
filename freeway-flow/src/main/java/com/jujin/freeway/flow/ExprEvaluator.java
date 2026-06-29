package com.jujin.freeway.flow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 极简条件表达式求值器（零外部依赖，递归下降解析）
 *
 * <p>支持语法：
 * <pre>
 * expression  → or_expr
 * or_expr     → and_expr ("||" and_expr)*
 * and_expr    → cmp_expr ("&&" cmp_expr)*
 * cmp_expr    → add_expr (("=="|"!="|">"|"<"|">="|"<=") add_expr)?
 * add_expr    → unary_expr (("+"|"-") unary_expr)*
 * unary_expr  → "!" unary_expr | primary
 * primary     → NUMBER | STRING | "true" | "false" | "null" | IDENT | "(" expression ")"
 * IDENT       → [a-zA-Z_][a-zA-Z0-9_.]*  (支持 data.name 路径访问)
 * </pre>
 *
 * 表达式在首次使用时编译为 AST（抽象语法树），后续相同表达式直接求值，避免重复解析。
 *
 * @author noear (solon-flow), adapted for freeway
 */
public final class ExprEvaluator {

    private static final int CACHE_MAX = 512;

    private static final Map<String, AstNode> CACHE = new LinkedHashMap<>(CACHE_MAX, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AstNode> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private static final ReentrantReadWriteLock CACHE_LOCK = new ReentrantReadWriteLock();

    private ExprEvaluator() {}

    /**
     * 求值条件表达式，返回布尔值
     */
    public static boolean evalCondition(String expr, Map<String, Object> context) {
        if (expr == null || expr.isBlank()) return true;
        String key = expr.trim();
        AstNode node;
        CACHE_LOCK.readLock().lock();
        try {
            node = CACHE.get(key);
        } finally {
            CACHE_LOCK.readLock().unlock();
        }
        if (node == null) {
            node = Compiler.compile(key);
            CACHE_LOCK.writeLock().lock();
            try {
                CACHE.put(key, node);
            } finally {
                CACHE_LOCK.writeLock().unlock();
            }
        }
        Object val = node.eval(context);
        return val instanceof Boolean b ? b : val != null;
    }

    // ======================== AST 节点类型 ========================

    private interface AstNode {
        Object eval(Map<String, Object> ctx);
    }

    private record Literal(Object value) implements AstNode {
        @Override public Object eval(Map<String, Object> ctx) { return value; }
    }

    private record Ident(String name, String[] parts) implements AstNode {
        private Ident { if (parts == null) parts = name.split("\\."); }
        @Override public Object eval(Map<String, Object> ctx) {
            Object cur = ctx;
            for (String p : parts) {
                if (cur instanceof Map<?, ?> m) {
                    cur = m.get(p);
                } else if (cur instanceof List<?> list) {
                    try {
                        cur = list.get(Integer.parseInt(p));
                    } catch (NumberFormatException e) {
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
            Object l = left.eval(ctx), r = right.eval(ctx);
            return switch (op) {
                case "||", "or" -> toBool(l) || toBool(r);
                case "&&", "and" -> toBool(l) && toBool(r);
                case "==" -> eq(l, r);
                case "!=" -> !eq(l, r);
                case ">=" -> cmp(l, r) >= 0;
                case "<=" -> cmp(l, r) <= 0;
                case ">" -> cmp(l, r) > 0;
                case "<" -> cmp(l, r) < 0;
                case "+" -> add(l, r);
                case "-" -> sub(l, r);
                default -> throw new FlowException("Unknown operator: " + op);
            };
        }
    }

    private record UnaryOp(String op, AstNode child) implements AstNode {
        @Override public Object eval(Map<String, Object> ctx) {
            return switch (op) {
                case "!" -> !toBool(child.eval(ctx));
                case "not" -> !toBool(child.eval(ctx));
                default -> throw new FlowException("Unknown unary operator: " + op);
            };
        }
    }

    // ======================== 编译（解析 → AST，只执行一次） ========================

    private static final class Compiler {
        private final String expr;
        private int pos;
        private final int len;

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
            AstNode left = unaryExpr();
            skipSpaces();
            while (match("+") || match("-")) {
                String op = expr.charAt(pos - 1) == '-' ? "-" : "+";
                AstNode right = unaryExpr();
                left = new BinaryOp(left, op, right);
                skipSpaces();
            }
            return left;
        }

        private AstNode unaryExpr() {
            skipSpaces();
            if (match("!")) return new UnaryOp("!", unaryExpr());
            return primary();
        }

        private AstNode primary() {
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
                // 算子可能紧跟标识符（如 "!active"），此时不做 ident part 检查
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

    // ======================== 工具方法 ========================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number an && b instanceof Number bn)
            return Double.compare(an.doubleValue(), bn.doubleValue());
        if (a instanceof Comparable ca && b instanceof Comparable cb) {
            try { return ca.compareTo(b); } catch (Exception ignored) {}
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number an && b instanceof Number bn) return an.doubleValue() == bn.doubleValue();
        return a.equals(b);
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
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
}
