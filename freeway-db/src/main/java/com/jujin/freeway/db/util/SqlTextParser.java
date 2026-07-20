package com.jujin.freeway.db.util;

import com.jujin.freeway.db.util.Names;
import java.util.ArrayList;
import java.util.List;

public final class SqlTextParser {

    private SqlTextParser() {}

    public record Result(
        List<String> names,
        String sql,
        List<Integer> parameterIndexes
    ) {}

    public static Result parseNamed(String sql) {
        var names = new ArrayList<String>();
        var parameterIndexes = new ArrayList<Integer>();
        var sb = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = appendQuoted(sql, i, sb, '\'');
                continue;
            }
            if (c == '"') {
                i = appendQuoted(sql, i, sb, '"');
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = appendLineComment(sql, i, sb);
                continue;
            }
            if (c == '/') {
                int after = appendBlockComment(sql, i, sb);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '$') {
                int after = skipDollarQuote(sql, i, sb);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == ':' && i + 1 < len && sql.charAt(i + 1) == ':') {
                sb.append("::");
                i += 2;
                continue;
            }
            if ((c == ':' || c == '$') && i + 1 < len) {
                char next = sql.charAt(i + 1);
                if (Names.isValidParamStart(next)) {
                    int start = i + 1;
                    i += 2;
                    while (i < len && Names.isValidParamChar(sql.charAt(i))) {
                        i++;
                    }
                    names.add(sql.substring(start, i));
                    parameterIndexes.add(sb.length());
                    sb.append('?');
                    continue;
                }
            }
            sb.append(c);
            i++;
        }

        return new Result(
            List.copyOf(names),
            sb.toString(),
            List.copyOf(parameterIndexes)
        );
    }

    public static List<Integer> paramIndexes(String sql) {
        var indexes = new ArrayList<Integer>();
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (c == '"') {
                i = skipQuoted(sql, i, '"');
                continue;
            }
            if (c == '-') {
                int after = skipLineComment(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '/') {
                int after = skipBlockComment(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '$') {
                int after = skipDollarQuote(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '?') {
                indexes.add(i);
            }
            i++;
        }

        return List.copyOf(indexes);
    }

    public static boolean hasNamedPlaceholders(String sql) {
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (c == '"') {
                i = skipQuoted(sql, i, '"');
                continue;
            }
            if (c == '-') {
                int after = skipLineComment(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '/') {
                int after = skipBlockComment(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '$') {
                int after = skipDollarQuote(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == ':' && i + 1 < len && sql.charAt(i + 1) == ':') {
                i += 2;
                continue;
            }
            if (
                (c == ':' || c == '$') &&
                i + 1 < len &&
                Names.isValidParamStart(sql.charAt(i + 1))
            ) {
                return true;
            }
            i++;
        }
        return false;
    }

    public static boolean hasTopLevelInsert(String sql) {
        if (sql == null) {
            return false;
        }
        int len = sql.length();
        int i = 0;
        int depth = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (c == '"') {
                i = skipQuoted(sql, i, '"');
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (c == '$') {
                int after = skipDollarQuote(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')' && depth > 0) {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && Character.isLetter(c)) {
                int start = i;
                i++;
                while (i < len && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                if (i - start == 6 && sql.regionMatches(true, start, "insert", 0, 6)) {
                    return true;
                }
                continue;
            }
            i++;
        }
        return false;
    }

    public static List<String> splitStatements(String sql) {
        if (sql.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = appendQuoted(sql, i, current, '\'');
                continue;
            }
            if (c == '"') {
                i = appendQuoted(sql, i, current, '"');
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (c == '$') {
                int after = skipDollarQuote(sql, i, current);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == ';') {
                addStatement(statements, current);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            i++;
            if (c == quote) {
                if (i < len && sql.charAt(i) == quote) {
                    i++;
                } else {
                    break;
                }
            }
        }
        return i;
    }

    private static int appendQuoted(String sql, int start, StringBuilder current, char quote) {
        int i = start + 1;
        int len = sql.length();
        current.append(quote);
        while (i < len) {
            char c = sql.charAt(i);
            current.append(c);
            i++;
            if (c == quote) {
                if (i < len && sql.charAt(i) == quote) {
                    current.append(quote);
                    i++;
                } else {
                    break;
                }
            }
        }
        return i;
    }

    private static int skipLineComment(String sql, int start) {
        int i = start;
        int len = sql.length();
        if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
            i += 2;
            while (i < len && sql.charAt(i) != '\n') {
                i++;
            }
            return i;
        }
        return start;
    }

    private static int appendLineComment(String sql, int start, StringBuilder current) {
        int i = start;
        int len = sql.length();
        if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
            current.append("--");
            i += 2;
            while (i < len && sql.charAt(i) != '\n') {
                current.append(sql.charAt(i));
                i++;
            }
            return i;
        }
        return start;
    }

    private static int skipBlockComment(String sql, int start) {
        int i = start;
        int len = sql.length();
        if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
            i += 2;
            while (i < len) {
                char c = sql.charAt(i);
                i++;
                if (c == '*' && i < len && sql.charAt(i) == '/') {
                    i++;
                    break;
                }
            }
            return i;
        }
        return start;
    }

    private static int appendBlockComment(String sql, int start, StringBuilder current) {
        int i = start;
        int len = sql.length();
        if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
            current.append("/*");
            i += 2;
            while (i < len) {
                char c = sql.charAt(i);
                current.append(c);
                i++;
                if (c == '*' && i < len && sql.charAt(i) == '/') {
                    current.append('/');
                    i++;
                    break;
                }
            }
            return i;
        }
        return start;
    }

    private static int skipDollarQuote(String sql, int start) {
        return skipDollarQuote(sql, start, null);
    }

    private static int skipDollarQuote(String sql, int start, StringBuilder current) {
        int len = sql.length();
        int tagEnd = start + 1;
        if (tagEnd >= len) {
            return start;
        }
        if (!Character.isLetterOrDigit(sql.charAt(tagEnd)) && sql.charAt(tagEnd) != '_') {
            if (tagEnd < len && sql.charAt(tagEnd) == '$') {
                int end = start + 2;
                if (current != null) {
                    current.append(sql, start, end);
                }
                return skipUntilDollarQuoteEnd(sql, end, current, null);
            }
            return start;
        }

        while (tagEnd < len && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) {
            tagEnd++;
        }
        if (tagEnd >= len || sql.charAt(tagEnd) != '$') {
            return start;
        }
        String tag = sql.substring(start, tagEnd + 1);
        int bodyStart = tagEnd + 1;
        int end = sql.indexOf(tag, bodyStart);
        if (end < 0) {
            return start;
        }
        int after = end + tag.length();
        if (current != null) {
            current.append(sql, start, after);
        }
        return after;
    }

    private static int skipUntilDollarQuoteEnd(
        String sql,
        int start,
        StringBuilder current,
        String tag
    ) {
        int len = sql.length();
        int i = start;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '$') {
                if (tag == null) {
                    if (i + 1 < len && sql.charAt(i + 1) == '$') {
                        if (current != null) {
                            current.append("$$");
                        }
                        return i + 2;
                    }
                } else if (sql.startsWith(tag, i)) {
                    if (current != null) {
                        current.append(tag);
                    }
                    return i + tag.length();
                }
            }
            if (current != null) {
                current.append(c);
            }
            i++;
        }
        return start;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /**
     * Validates that the SQL does not mix named ({@code :name / $name}) and
     * positional ({@code ?}) placeholders. Throws {@link com.jujin.freeway.db.SqlException}
     * if both styles are present.
     */
    public static void requireNoMixedPlaceholders(String sql) {
        if (hasNamedPlaceholders(sql) && !paramIndexes(sql).isEmpty()) {
            throw new com.jujin.freeway.db.SqlException(
                "Cannot mix named and positional placeholders in SQL: " + sql);
        }
    }
}
