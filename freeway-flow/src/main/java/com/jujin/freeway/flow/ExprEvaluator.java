package com.jujin.freeway.flow;

import java.util.List;
import java.util.Map;

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
 * @author noear (solon-flow), adapted for freeway
 * @since 3.1
 */
public class ExprEvaluator {

    private final String expr;
    private final Map<String, Object> context;
    private int pos;
    private final int len;

    public ExprEvaluator(String expr, Map<String, Object> context) {
        this.expr = (expr != null) ? expr.trim() : "";
        this.context = context;
        this.pos = 0;
        this.len = this.expr.length();
    }

    /**
     * 求值条件表达式，返回布尔值
     */
    public static boolean evalCondition(String expr, Map<String, Object> context) {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        Object val = new ExprEvaluator(expr, context).parse();
        if (val == null) {
            return false;
        }
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return true;
    }

    /// --- parser ---

    private Object parse() {
        Object result = orExpr();
        skipSpaces();
        if (pos < len) {
            throw new FlowException("Unexpected token at position " + pos + ": '" + expr.charAt(pos) + "' in '" + expr + "'");
        }
        return result;
    }

    private Object orExpr() {
        Object left = andExpr();
        skipSpaces();
        while (match("||") || matchIgnoreCase("or")) {
            Object right = andExpr();
            left = toBool(left) || toBool(right);
            skipSpaces();
        }
        return left;
    }

    private Object andExpr() {
        Object left = cmpExpr();
        skipSpaces();
        while (match("&&") || matchIgnoreCase("and")) {
            Object right = cmpExpr();
            left = toBool(left) && toBool(right);
            skipSpaces();
        }
        return left;
    }

    private Object cmpExpr() {
        Object left = addExpr();
        skipSpaces();
        if (match("==")) {
            Object right = addExpr();
            return eq(left, right);
        } else if (match("!=")) {
            Object right = addExpr();
            return !eq(left, right);
        } else if (match(">=")) {
            Object right = addExpr();
            return compare(left, right) >= 0;
        } else if (match("<=")) {
            Object right = addExpr();
            return compare(left, right) <= 0;
        } else if (match(">")) {
            Object right = addExpr();
            return compare(left, right) > 0;
        } else if (match("<")) {
            Object right = addExpr();
            return compare(left, right) < 0;
        }
        return left;
    }

    private Object addExpr() {
        Object left = unaryExpr();
        skipSpaces();
        while (match("+") || match("-")) {
            boolean isMinus = expr.charAt(pos - 1) == '-';
            Object right = unaryExpr();
            if (left instanceof Number && right instanceof Number) {
                double l = ((Number) left).doubleValue();
                double r = ((Number) right).doubleValue();
                left = isMinus ? (l - r) : (l + r);
            } else if (isMinus) {
                throw new FlowException("Cannot subtract non-numeric values: " + left + " - " + right);
            } else {
                // string concat
                left = String.valueOf(left) + String.valueOf(right);
            }
            skipSpaces();
        }
        return left;
    }

    private Object unaryExpr() {
        skipSpaces();
        if (match("!")) {
            Object v = unaryExpr();
            return !toBool(v);
        }
        return primary();
    }

    private Object primary() {
        skipSpaces();
        if (pos >= len) {
            throw new FlowException("Unexpected end of expression: '" + expr + "'");
        }

        char c = expr.charAt(pos);

        // 数字
        if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
            return number();
        }

        // 字符串
        if (c == '\'' || c == '"') {
            return string();
        }

        // 括号
        if (c == '(') {
            pos++;
            Object val = orExpr();
            skipSpaces();
            if (pos < len && expr.charAt(pos) == ')') {
                pos++;
            } else {
                throw new FlowException("Missing closing ')' at position " + pos + " in '" + expr + "'");
            }
            return val;
        }

        // 标识符或关键字
        if (isIdentStart(c)) {
            String ident = ident();
            return switch (ident) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                case "not" -> !toBool(unaryExpr());
                default -> resolveIdent(ident);
            };
        }

        throw new FlowException("Unexpected character '" + c + "' at position " + pos + " in '" + expr + "'");
    }

    /// --- helpers ---

    private Number number() {
        int start = pos;
        if (pos < len && (expr.charAt(pos) == '-' || expr.charAt(pos) == '+')) {
            pos++;
        }
        boolean isDecimal = false;
        while (pos < len) {
            char c = expr.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' && !isDecimal) {
                isDecimal = true;
                pos++;
            } else {
                break;
            }
        }
        String numStr = expr.substring(start, pos);
        try {
            if (isDecimal) {
                return Double.parseDouble(numStr);
            } else {
                long v = Long.parseLong(numStr);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                    return (int) v;
                }
                return v;
            }
        } catch (NumberFormatException e) {
            throw new FlowException("Invalid number: '" + numStr + "' in '" + expr + "'", e);
        }
    }

    private String string() {
        char quote = expr.charAt(pos);
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = expr.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos < len) {
                    switch (expr.charAt(pos)) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        case '\'' -> sb.append('\'');
                        default -> sb.append(expr.charAt(pos));
                    }
                    pos++;
                }
            } else if (c == quote) {
                pos++;
                return sb.toString();
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new FlowException("Unterminated string starting at position in '" + expr + "'");
    }

    private String ident() {
        int start = pos;
        while (pos < len) {
            char c = expr.charAt(pos);
            if (isIdentPart(c)) {
                pos++;
            } else {
                break;
            }
        }
        return expr.substring(start, pos);
    }

    /**
     * 从上下文解析标识符（支持点号路径访问）
     */
    private Object resolveIdent(String ident) {
        String[] parts = ident.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else if (current instanceof List) {
                try {
                    int idx = Integer.parseInt(part);
                    current = ((List<?>) current).get(idx);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /// --- token matching ---

    private void skipSpaces() {
        while (pos < len && Character.isWhitespace(expr.charAt(pos))) {
            pos++;
        }
    }

    private boolean match(String token) {
        skipSpaces();
        if (expr.startsWith(token, pos)) {
            // 确保不是标识符的一部分
            int end = pos + token.length();
            if (end <= len &&
                    (end == len || !isIdentPart(expr.charAt(end)) || !isIdentStart(token.charAt(0)))) {
                pos = end;
                return true;
            }
        }
        return false;
    }

    private boolean matchIgnoreCase(String token) {
        skipSpaces();
        if (pos + token.length() <= len) {
            String sub = expr.substring(pos, pos + token.length());
            if (sub.equalsIgnoreCase(token)) {
                int end = pos + token.length();
                if (end == len || !isIdentPart(expr.charAt(end))) {
                    pos = end;
                    return true;
                }
            }
        }
        return false;
    }

    /// --- comparison / coercion ---

    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && b instanceof Comparable) {
            try {
                return ((Comparable) a).compareTo(b);
            } catch (Exception e) {
                // fall through
            }
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static boolean eq(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '.';
    }
}
