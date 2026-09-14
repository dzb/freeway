package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.coercion.Coercer;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Static JSON entry points: parsing, serializing and coercing.
 *
 * <p><b>Stream ownership.</b> Every {@code parse*(InputStream)} overload
 * <em>closes</em> the stream it is given (the parser wraps it in
 * try-with-resources). Callers that need the stream afterwards must hand over a
 * wrapper, or use a {@code String} overload.</p>
 *
 * <p><b>Failure.</b> Malformed input throws {@link JsonException}; a
 * {@code parseObject}/{@code parseArray} on a different top-level shape throws
 * the same. Absent keys never throw — the getters on {@link JsonObject} /
 * {@link JsonArray} return {@code null}/empty, while a type mismatch inside a
 * typed getter throws {@code IllegalArgumentException}.</p>
 *
 * <p><b>Coercion.</b> {@code coerce} uses a built-in {@link Coercer}; an
 * application that registers its own coercion rules must pass its container's
 * coercer explicitly ({@link JsonCodecDefault} does).</p>
 */
public final class JsonUtils {

    private JsonUtils() {}

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    public static Object parse(String text) {
        return JsonParser.parse(text);
    }

    public static JsonObject parseObject(String text) {
        return JsonParser.parseObject(text);
    }

    public static JsonArray parseArray(String text) {
        return JsonParser.parseArray(text);
    }

    public static Object parse(InputStream input) {
        return JsonParser.parse(input);
    }

    public static JsonObject parseObject(InputStream input) {
        return JsonParser.parseObject(input);
    }

    public static JsonArray parseArray(InputStream input) {
        return JsonParser.parseArray(input);
    }

    public static <T> T coerce(Object value, Class<T> targetType) {
        return JsonCoercions.coerce(value, targetType);
    }

    public static <T> T coerce(
        Object value,
        Class<T> targetType,
        Coercer coercer
    ) {
        return JsonCoercions.coerce(value, targetType, coercer);
    }

    public static Object coerce(Object value, Type type) {
        return JsonCoercions.coerce(value, type);
    }

    public static Object coerce(Object value, Type type, Coercer coercer) {
        return JsonCoercions.coerce(value, type, coercer);
    }

    public static String stringify(Object value) {
        return JsonWriter.stringify(value);
    }

    public static String stringifyPretty(Object value) {
        return JsonWriter.stringifyPretty(value);
    }

    public static Object normalize(Object value) {
        return JsonCoercions.normalize(value);
    }
}
