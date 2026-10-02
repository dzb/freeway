package com.jujin.freeway.commons.json;

import java.lang.reflect.Type;

/**
 * Object ↔ JSON text with an injected {@link Coercer}, for an application
 * that has one — the container's, or its own.
 *
 * <p>The counterpart to {@link JsonUtils}, which does the same conversions
 * with a built-in coercer and no instance. This interface covers text and
 * types only: the node layer ({@link JsonObject} / {@link JsonArray}, the
 * {@code parse*} family) stays on the static side, because a caller holding a
 * codec already has a coercer and does not need the built-in coercion rules.
 */
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
