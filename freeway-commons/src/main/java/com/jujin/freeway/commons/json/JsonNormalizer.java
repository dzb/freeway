package com.jujin.freeway.commons.json;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;

final class JsonNormalizer {
    private static final int MAX_DEPTH = 1000;

    private JsonNormalizer() {
    }

    static Object normalize(Object value) {
        return normalize(value, new Context(), 0);
    }

    private static Object normalize(Object value, Context context, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON value nesting too deep (max " + MAX_DEPTH + " levels)");
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
            return normalizeMap(map, context, depth);
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return normalizeArray(value, context, depth);
        }
        if (value instanceof Iterable<?> iterable) {
            return normalizeIterable(iterable, context, depth);
        }
        return normalizeBean(value, context, depth);
    }

    static Object deepCopy(Object value) {
        return deepCopy(value, new Context(), 0);
    }

    private static Object deepCopy(Object value, Context context, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON value nesting too deep (max " + MAX_DEPTH + " levels)");
        }
        if (value instanceof JsonObject object) {
            context.enter(object);
            try {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                object.forEach((key, item) -> copy.put(key, deepCopy(item, context, depth + 1)));
                return copy;
            } finally {
                context.exit(object);
            }
        }
        if (value instanceof JsonArray array) {
            context.enter(array);
            try {
                ArrayList<Object> copy = new ArrayList<>(array.size());
                for (int i = 0; i < array.size(); i++) {
                    copy.add(deepCopy(array.get(i), context, depth + 1));
                }
                return copy;
            } finally {
                context.exit(array);
            }
        }
        return value;
    }

    private static JsonObject normalizeBean(Object value, Context context, int depth) {
        context.enter(value);
        try {
            BeanPlan plan = BeanIntrospector.plan(value.getClass());
            JsonObject object = JsonUtils.object();
            for (BeanProperty property : plan.properties()) {
                object.put(property.name(), normalize(property.read(value), context, depth + 1));
            }
            return object;
        } finally {
            context.exit(value);
        }
    }

    private static JsonArray normalizeArray(Object array, Context context, int depth) {
        context.enter(array);
        try {
            JsonArray result = JsonUtils.array();
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                result.add(normalize(Array.get(array, i), context, depth + 1));
            }
            return result;
        } finally {
            context.exit(array);
        }
    }

    private static JsonArray normalizeIterable(Iterable<?> iterable, Context context, int depth) {
        context.enter(iterable);
        try {
            JsonArray result = JsonUtils.array();
            for (Object item : iterable) {
                result.add(normalize(item, context, depth + 1));
            }
            return result;
        } finally {
            context.exit(iterable);
        }
    }

    private static JsonObject normalizeMap(Map<?, ?> map, Context context, int depth) {
        context.enter(map);
        try {
            JsonObject result = JsonUtils.object();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), normalize(entry.getValue(), context, depth + 1));
            }
            return result;
        } finally {
            context.exit(map);
        }
    }

    static Map<String, Object> deepCopyObject(JsonObject object) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) deepCopy(object);
        return copy;
    }

    static List<Object> deepCopyArray(JsonArray array) {
        @SuppressWarnings("unchecked")
        List<Object> copy = (List<Object>) deepCopy(array);
        return copy;
    }

    private static final class Context {
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();

        void enter(Object value) {
            if (active.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Cyclic JSON value");
            }
        }

        void exit(Object value) {
            active.remove(value);
        }
    }
}
