package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;

import java.math.BigDecimal;

final class JsonAccessors {

    private JsonAccessors() {}

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The scalar readers delegate to the same {@link Coercer} the typed JSON
     * path uses, so a node getter and {@code JsonCodec.convert} cannot disagree
     * about "1", "yes" or "1.5". They used to be a second, stricter rule set
     * (JSON literals only), which made {@code {"b":1}} readable through one
     * path and rejected through the other. The built-in coercer is used here
     * because a tree node has no container; an application's own
     * {@code CoerceRule}s apply through {@code JsonCodec}, not through the
     * node getters.
     */
    private static final Coercer SCALARS = new CoercerDefault();

    static Integer integer(Object value) {
        return value == null ? null : SCALARS.coerce(value, Integer.class);
    }

    static Long longValue(Object value) {
        return value == null ? null : SCALARS.coerce(value, Long.class);
    }

    static Double doubleValue(Object value) {
        return value == null ? null : SCALARS.coerce(value, Double.class);
    }

    static Boolean booleanValue(Object value) {
        return value == null ? null : SCALARS.coerce(value, Boolean.class);
    }

    static JsonObject object(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException(
            "Expected a JSON object, found " + JsonUtils.typeName(value));
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

    /** The value as an array; see {@link #object(Object)} for the null/throw rule. */
    static JsonArray array(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new IllegalArgumentException(
            "Expected a JSON array, found " + JsonUtils.typeName(value));
    }
}
