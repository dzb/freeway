package com.jujin.freeway2.web;

import com.jujin.freeway2.commons.json.JsonUtils;
import com.jujin.freeway2.commons.scalar.Coercer;
import java.lang.reflect.Type;
import java.util.Objects;

final class DefaultJsonCodec implements JsonCodec {
    private final Coercer coercer;

    DefaultJsonCodec(Coercer coercer) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public String toJson(Object value) {
        return JsonUtils.stringify(JsonUtils.normalize(value));
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        return JsonUtils.coerce(JsonUtils.parse(json), type, coercer::coerce);
    }

    @Override
    public <T> T fromJson(String json, Type type) {
        @SuppressWarnings("unchecked")
        T value = (T) JsonUtils.coerce(JsonUtils.parse(json), type, coercer::coerce);
        return value;
    }
}
