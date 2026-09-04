package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import java.lang.reflect.Type;
import java.util.Objects;

/** Default {@link JsonCodec} implementation backed by {@link JsonUtils} and a {@link Coercer}. */
public final class JsonCodecDefault implements JsonCodec {

    private final Coercer coercer;

    public JsonCodecDefault() {
        this(new CoercerDefault());
    }

    public JsonCodecDefault(Coercer coercer) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public String toJson(Object value) {
        return JsonUtils.stringify(value);
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        return JsonUtils.coerce(JsonUtils.parse(json), type, coercer::coerce);
    }

    @Override
    public <T> T fromJson(String json, Type type) {
        @SuppressWarnings("unchecked")
        T value = (T) JsonUtils.coerce(
            JsonUtils.parse(json),
            type,
            coercer::coerce
        );
        return value;
    }
}
