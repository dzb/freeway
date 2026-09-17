package com.jujin.freeway.commons.json;

import java.lang.reflect.Type;

public interface JsonCodec {
    String toJson(Object value);

    <T> T fromJson(String json, Class<T> type);

    <T> T fromJson(String json, Type type);

    /**
     * Re-types an already-parsed JSON node (the {@code Map}/{@code List}/
     * leaf shape produced by {@link JsonUtils#parse}) as {@code type},
     * using this codec's coercion. The default round-trips through text;
     * an implementation that parses with its own coercer should override
     * with the direct node coercion instead.
     */
    default <T> T convert(Object node, Class<T> type) {
        return convert(node, (Type) type);
    }

    default <T> T convert(Object node, Type type) {
        return fromJson(toJson(node), type);
    }
}
