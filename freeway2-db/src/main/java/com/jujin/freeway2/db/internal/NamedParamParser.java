package com.jujin.freeway2.db.internal;

import java.util.ArrayList;
import java.util.List;

final class NamedParamParser {
    private NamedParamParser() {
    }

    record Result(List<String> names, String jdbcSql) {
    }

    static Result parse(String sql) {
        var names = new ArrayList<String>();
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
                while (i < len && sql.charAt(i) != '"') {
                    sb.append(sql.charAt(i));
                    i++;
                }
                if (i < len) {
                    sb.append('"');
                    i++;
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
            if ((c == ':' || c == '#') && i + 1 < len) {
                char next = sql.charAt(i + 1);
                if (isValidParamStart(next)) {
                    int start = i + 1;
                    i += 2;
                    while (i < len && isValidParamChar(sql.charAt(i))) {
                        i++;
                    }
                    names.add(sql.substring(start, i));
                    sb.append('?');
                    continue;
                }
            }
            sb.append(c);
            i++;
        }

        return new Result(names, sb.toString());
    }

    private static boolean isValidParamStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isValidParamChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
