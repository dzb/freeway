package com.jujin.freeway.commons.json;

final class JsonAccessors {
    private JsonAccessors() {
    }

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    static Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static JsonObject object(Object value) {
        return value instanceof JsonObject object ? object : null;
    }

    static JsonArray array(Object value) {
        return value instanceof JsonArray array ? array : null;
    }
}
