package com.jujin.freeway.commons.json;

import java.lang.reflect.Array;
import java.util.Map;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;

final class JsonNormalizer {
    private JsonNormalizer() {
    }

    static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject || value instanceof JsonArray || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return normalizeArray(value);
        }
        if (value instanceof Iterable<?> iterable) {
            return normalizeIterable(iterable);
        }
        return normalizeBean(value);
    }

    static Object deepCopy(Object value) {
        if (value instanceof JsonObject object) {
            return object.toMap();
        }
        if (value instanceof JsonArray array) {
            return array.toList();
        }
        return value;
    }

    private static JsonObject normalizeBean(Object value) {
        BeanPlan plan = BeanIntrospector.plan(value.getClass());
        JsonObject object = JsonUtils.object();
        for (BeanProperty property : plan.properties()) {
            object.put(property.name(), property.read(value));
        }
        return object;
    }

    private static JsonArray normalizeArray(Object array) {
        JsonArray result = JsonUtils.array();
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            result.add(Array.get(array, i));
        }
        return result;
    }

    private static JsonArray normalizeIterable(Iterable<?> iterable) {
        JsonArray result = JsonUtils.array();
        for (Object item : iterable) {
            result.add(item);
        }
        return result;
    }

    private static JsonObject normalizeMap(Map<?, ?> map) {
        JsonObject result = JsonUtils.object();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
