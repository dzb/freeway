package com.jujin.freeway2.commons.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        Object value = get(index);
        return value == null ? null : String.valueOf(value);
    }

    public Integer getInt(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public Long getLong(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public Double getDouble(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    public Boolean getBoolean(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public JsonObject getObject(int index) {
        Object value = get(index);
        if (value instanceof JsonObject object) {
            return object;
        }
        return null;
    }

    public JsonArray getArray(int index) {
        Object value = get(index);
        if (value instanceof JsonArray array) {
            return array;
        }
        return null;
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
        ArrayList<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) {
            copy.add(JsonUtils.deepCopy(value));
        }
        return copy;
    }
}
