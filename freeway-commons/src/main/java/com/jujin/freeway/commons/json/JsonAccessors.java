package com.jujin.freeway.commons.json;

import java.math.BigDecimal;

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
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse '" + value + "' as Integer");
        }
    }

    static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse '" + value + "' as Long");
        }
    }

    static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse '" + value + "' as Double");
        }
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

    static BigDecimal bigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse '" + value + "' as BigDecimal");
        }
    }

    static JsonArray array(Object value) {
        return value instanceof JsonArray array ? array : null;
    }
}
