package com.jujin.freeway.commons.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class JsonArray {

    private final ArrayList<Object> values;

    JsonArray() {
        this.values = new ArrayList<>();
    }

    public JsonArray add(Object value) {
        values.add(JsonUtils.normalize(value));
        return this;
    }

    public JsonObject object() {
        JsonObject value = new JsonObject();
        values.add(value);
        return value;
    }

    public JsonArray array() {
        JsonArray value = new JsonArray();
        values.add(value);
        return value;
    }

    public Object get(int index) {
        return values.get(index);
    }

    public String getString(int index) {
        return JsonAccessors.string(get(index));
    }

    public Integer getInt(int index) {
        return JsonAccessors.integer(get(index));
    }

    public Long getLong(int index) {
        return JsonAccessors.longValue(get(index));
    }

    public Double getDouble(int index) {
        return JsonAccessors.doubleValue(get(index));
    }

    public BigDecimal getBigDecimal(int index) {
        return JsonAccessors.bigDecimal(get(index));
    }

    public Boolean getBoolean(int index) {
        return JsonAccessors.booleanValue(get(index));
    }

    public JsonObject getObject(int index) {
        return JsonAccessors.object(get(index));
    }

    public JsonArray getArray(int index) {
        return JsonAccessors.array(get(index));
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JsonArray that = (JsonArray) o;
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

    public List<Object> toList() {
        return JsonNormalizer.deepCopyArray(this);
    }
}
