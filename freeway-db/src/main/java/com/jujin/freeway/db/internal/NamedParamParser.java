package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.util.Names;
import java.util.ArrayList;
import java.util.List;

final class NamedParamParser {

    private NamedParamParser() {}

    record Result(
        List<String> names,
        String sql,
        List<Integer> parameterIndexes
    ) {}

    static Result parse(String sql) {
        var names = new ArrayList<String>();
        var parameterIndexes = new ArrayList<Integer>();
        var sb = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                sb.append(c);
                i++;
                while (i < len) {
                    char sc = sql.charAt(i);
                    sb.append(sc);
                    i++;
                    if (sc == '\'') {
                        if (i < len && sql.charAt(i) == '\'') {
                            sb.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '"') {
                sb.append(c);
                i++;
                while (i < len) {
                    char dc = sql.charAt(i);
                    sb.append(dc);
                    i++;
                    if (dc == '"') {
                        if (i < len && sql.charAt(i) == '"') {
                            sb.append('"');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                sb.append(c);
                i++;
                sb.append('-');
                i++;
                while (i < len && sql.charAt(i) != '\n') {
                    sb.append(sql.charAt(i));
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                sb.append('/');
                i++;
                sb.append('*');
                i++;
                while (i < len) {
                    char bc = sql.charAt(i);
                    sb.append(bc);
                    i++;
                    if (bc == '*' && i < len && sql.charAt(i) == '/') {
                        sb.append('/');
                        i++;
                        break;
                    }
                }
                continue;
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

    static List<Integer> positionalPlaceholderIndexes(String sql) {
        var indexes = new ArrayList<Integer>();
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i++;
                while (i < len) {
                    char sc = sql.charAt(i);
                    i++;
                    if (sc == '\'') {
                        if (i < len && sql.charAt(i) == '\'') {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < len) {
                    char dc = sql.charAt(i);
                    i++;
                    if (dc == '"') {
                        if (i < len && sql.charAt(i) == '"') {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    char bc = sql.charAt(i);
                    i++;
                    if (bc == '*' && i < len && sql.charAt(i) == '/') {
                        i++;
                        break;
                    }
                }
                continue;
            }
            if (c == '?') {
                indexes.add(i);
            }
            i++;
        }

        return List.copyOf(indexes);
    }

    /**
     * Lightweight scan: returns true if {@code sql} contains at least one
     * {@code :name} or {@code $name} placeholder (outside of string literals,
     * quoted identifiers, and comments).
     */
    static boolean hasNamedPlaceholders(String sql) {
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipString(sql, i, '\'');
                continue;
            }
            if (c == '"') {
                i = skipString(sql, i, '"');
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                while (i < len && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (
                        i < len &&
                        !(sql.charAt(i - 1) == '*' && sql.charAt(i) == '/')
                    )
                    i++;
                i++;
                continue;
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

    private static int skipString(String sql, int start, char quote) {
        int i = start + 1;
        int len = sql.length();
        while (i < len) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return len;
    }
}
