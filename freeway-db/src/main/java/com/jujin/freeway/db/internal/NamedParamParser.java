package com.jujin.freeway.db.internal;

import java.util.ArrayList;
import java.util.List;

final class NamedParamParser {
    private NamedParamParser() {
    }

    record Result(List<String> names, String sql, List<Integer> parameterIndexes) {
    }

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
                if (isValidParamStart(next)) {
                    int start = i + 1;
                    i += 2;
                    while (i < len && isValidParamChar(sql.charAt(i))) {
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

    private static boolean isValidParamStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isValidParamChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
