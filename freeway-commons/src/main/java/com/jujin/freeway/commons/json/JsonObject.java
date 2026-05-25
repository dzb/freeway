package com.jujin.freeway.commons.json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public final class JsonObject {
    private final LinkedHashMap<String, Object> values;

    JsonObject() {
        this.values = new LinkedHashMap<>();
    }

    public JsonObject put(String key, Object value) {
        values.put(Objects.requireNonNull(key, "key"), JsonUtils.normalize(value));
        return this;
    }

    public JsonObject object(String key) {
        JsonObject value = new JsonObject();
        put(key, value);
        return value;
    }

    public JsonArray array(String key) {
        JsonArray value = new JsonArray();
        put(key, value);
        return value;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String getString(String key) {
        Object value = get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Integer getInt(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public Long getLong(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public Double getDouble(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    public Boolean getBoolean(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public JsonObject getObject(String key) {
        Object value = get(key);
        if (value instanceof JsonObject object) {
            return object;
        }
        return null;
    }

    public JsonArray getArray(String key) {
        Object value = get(key);
        if (value instanceof JsonArray array) {
            return array;
        }
        return null;
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Set<String> keySet() {
        return Set.copyOf(values.keySet());
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, JsonUtils.deepCopy(value)));
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JsonObject that = (JsonObject) o;
        return values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return JsonUtils.stringify(this);
    }

    void forEach(BiConsumer<String, Object> consumer) {
        values.forEach(consumer);
    }
}
