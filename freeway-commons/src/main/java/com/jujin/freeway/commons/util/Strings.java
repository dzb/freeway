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
                // Split before an uppercase letter when the previous char is
                // lowercase/digit, or when it ends an acronym ("HTTPServer" →
                // "http_server", not "h_t_t_p_server"). Never double a
                // separator the identifier already contains ("user_Name" →
                // "user_name", not "user__name").
                if (i > 0) {
                    char prev = name.charAt(i - 1);
                    boolean prevLower =
                        Character.isLowerCase(prev) || Character.isDigit(prev);
                    boolean nextLower = i + 1 < name.length()
                        && Character.isLowerCase(name.charAt(i + 1));
                    if ((prevLower || nextLower) && prev != '_') {
                        sb.append('_');
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
