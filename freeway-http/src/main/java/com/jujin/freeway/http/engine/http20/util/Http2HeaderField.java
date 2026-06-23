package com.jujin.freeway.http.engine.http20.util;

public final class Http2HeaderField {
    public String name, value, normalizedName;

    public Http2HeaderField() {
    }

    public Http2HeaderField(String n, String v) {
        name = n;
        value = v;
        normalizedName = normalize(n);
    }

    public static String normalize(String v) {
        if (v == null || v.isEmpty()) return v;
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    public boolean isPseudoHeader() {
        return name != null && name.startsWith(":");
    }

    public String toString() {
        return name + ": " + value;
    }
}
