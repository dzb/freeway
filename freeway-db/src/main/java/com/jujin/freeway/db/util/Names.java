package com.jujin.freeway.db.util;

public final class Names {
    private Names() {}

    public static String camelToSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean isValidParamStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    public static boolean isValidParamChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
