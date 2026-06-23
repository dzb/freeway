package com.jujin.freeway.commons.json;

import java.io.InputStream;
import java.lang.reflect.Type;
import com.jujin.freeway.commons.coercion.Coercer;

public final class JsonUtils {
    private JsonUtils() {
    }

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

    public static <T> T coerce(Object value, Class<T> targetType, Coercer coercer) {
        return JsonCoercions.coerce(value, targetType, coercer);
    }

    public static Object coerce(Object value, Type type) {
        return JsonCoercions.coerce(value, type);
    }

    public static Object coerce(Object value, Type type, Coercer coercer) {
        return JsonCoercions.coerce(value, type, coercer);
    }

    public static String stringify(Object value) {
        return JsonWriter.stringify(normalize(value));
    }

    public static String stringifyPretty(Object value) {
        return JsonWriter.stringifyPretty(normalize(value));
    }

    public static Object normalize(Object value) {
        return JsonCoercions.normalize(value);
    }
}
