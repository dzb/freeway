package com.jujin.freeway.commons.json;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight JSON object backed by a {@link LinkedHashMap} — insertion
 * order is preserved. Both parsing and {@link #put(String, Object)} follow
 * <b>last-wins</b> semantics: setting an existing key replaces its value, so
 * {@code {"a":1,"a":2}} parses to {@code {"a":2}} and
 * {@code object.put("a", 1).put("a", 2)} ends with {@code 2}. No
 * duplicate-key diagnostics are produced — callers that need strict
 * uniqueness must validate their input.
 */
public final class JsonObject {

    private final LinkedHashMap<String, Object> values;

    JsonObject() {
        this.values = new LinkedHashMap<>();
    }

    /**
     * Sets {@code key} to {@code value}, replacing any previous value for the
     * same key (last-wins, like parsing). The value is
     * {@link JsonUtils#normalize(Object) normalized} into JSON form.
     */
    public JsonObject put(String key, Object value) {
        values.put(
            Objects.requireNonNull(key, "key"),
            JsonUtils.normalize(value)
        );
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
        return JsonAccessors.string(get(key));
    }

    public Integer getInt(String key) {
        return JsonAccessors.integer(get(key));
    }

    public Long getLong(String key) {
        return JsonAccessors.longValue(get(key));
    }

    public Double getDouble(String key) {
        return JsonAccessors.doubleValue(get(key));
    }

    public BigDecimal getBigDecimal(String key) {
        return JsonAccessors.bigDecimal(get(key));
    }

    public Boolean getBoolean(String key) {
        return JsonAccessors.booleanValue(get(key));
    }

    public JsonObject getObject(String key) {
        return JsonAccessors.object(get(key));
    }

    public JsonArray getArray(String key) {
        return JsonAccessors.array(get(key));
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
        return new LinkedHashSet<>(values.keySet());
    }

    public Map<String, Object> toMap() {
        return JsonNormalizer.deepCopyObject(this);
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

    /**
     * Package-private live entry view for internal iteration without
     * per-entry lambda allocation.
     */
    Iterable<Map.Entry<String, Object>> entries() {
        return values.entrySet();
    }
}
