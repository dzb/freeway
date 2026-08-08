package com.jujin.freeway.commons.json;

import java.math.BigDecimal;
import java.math.BigInteger;

final class JsonAccessors {

    private JsonAccessors() {}

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            if (number instanceof BigInteger bi) {
                if (
                    bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0 ||
                    bi.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                ) {
                    throw new IllegalArgumentException(
                        "Number " + number + " is out of Integer range"
                    );
                }
                return bi.intValue();
            }
            if (number instanceof BigDecimal bd) {
                if (
                    bd.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ||
                    bd.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                ) {
                    throw new IllegalArgumentException(
                        "Number " + number + " is out of Integer range"
                    );
                }
                return bd.intValue();
            }
            if (number instanceof Double d && (d.isNaN() || d.isInfinite())) {
                throw new IllegalArgumentException(
                    "Cannot convert " + number + " to Integer"
                );
            }
            if (number instanceof Float f && (f.isNaN() || f.isInfinite())) {
                throw new IllegalArgumentException(
                    "Cannot convert " + number + " to Integer"
                );
            }
            long v = number.longValue();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "Number " + number + " is out of Integer range"
                );
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Cannot parse '" + value + "' as Integer"
            );
        }
    }

    static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            if (number instanceof BigInteger) {
                BigInteger bi = (BigInteger) number;
                if (
                    bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ||
                    bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                ) {
                    throw new IllegalArgumentException(
                        "Number " + number + " is out of Long range"
                    );
                }
                return bi.longValue();
            }
            if (number instanceof BigDecimal) {
                BigDecimal bd = (BigDecimal) number;
                if (
                    bd.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ||
                    bd.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                ) {
                    throw new IllegalArgumentException(
                        "Number " + number + " is out of Long range"
                    );
                }
                return bd.longValue();
            }
            if (number instanceof Double d) {
                if (d.isNaN() || d.isInfinite()) {
                    throw new IllegalArgumentException(
                        "Cannot convert " + number + " to Long"
                    );
                }
                // (long) silently saturates out-of-range doubles to
                // Long.MAX/MIN — reject like the BigDecimal branch does.
                // 0x1p63 = 2^63, the double Long.MAX_VALUE rounds to.
                if (d >= 0x1p63 || d < -0x1p63) {
                    throw new IllegalArgumentException(
                        "Number " + number + " is out of Long range"
                    );
                }
            }
            if (number instanceof Float f) {
                if (f.isNaN() || f.isInfinite()) {
                    throw new IllegalArgumentException(
                        "Cannot convert " + number + " to Long"
                    );
                }
                if (f >= 0x1p63f || f < -0x1p63f) {
                    throw new IllegalArgumentException(
                        "Number " + number + " is out of Long range"
                    );
                }
            }
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Cannot parse '" + value + "' as Long"
            );
        }
    }

    static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isInfinite(d)) {
                throw new IllegalArgumentException(
                    "Number " + number + " is out of Double range"
                );
            }
            return d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Cannot parse '" + value + "' as Double"
            );
        }
    }

    static Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value);
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw new IllegalArgumentException(
            "Cannot parse '" + value + "' as Boolean"
        );
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
            throw new IllegalArgumentException(
                "Cannot parse '" + value + "' as BigDecimal"
            );
        }
    }

    static JsonArray array(Object value) {
        return value instanceof JsonArray array ? array : null;
    }
}
