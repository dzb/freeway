package com.jujin.freeway.commons.util;

public final class Strings {

    private Strings() {}

    /** Returns {@code null} if the string is null or blank, otherwise the original string. */
    public static String blankToNull(String s) {
        return s != null && !s.isBlank() ? s : null;
    }

    /** Converts {@code camelCase} to {@code snake_case}. */
    public static String camelToSnake(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
