package com.jujin.freeway.db.util;

public final class Names {
    private Names() {}

    public static boolean isValidParamStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    public static boolean isValidParamChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
