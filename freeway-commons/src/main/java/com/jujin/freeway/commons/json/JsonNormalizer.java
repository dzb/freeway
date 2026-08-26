package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Normalizes a raw value tree into {@link JsonObject}/{@link JsonArray}/
 * scalar form, mirroring {@link JsonWriter} leaf conversions via
 * {@link JsonLeaves}. Bean serialization follows the {@link BeanPlan}
 * property model: fields define the property set, {@code getX()}/{@code isX()}
 * accessors are the preferred read path, and getter-only (computed)
 * properties are included as read-only members.
 */
final class JsonNormalizer {

    private static final int MAX_DEPTH = JsonParser.MAX_DEPTH;

    private JsonNormalizer() {}

    static Object normalize(Object value) {
        return normalize(value, new Context(), 0);
    }

    private static Object normalize(Object value, Context context, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "JSON value nesting too deep (max " + MAX_DEPTH + " levels)"
            );
        }
        if (
            value instanceof JsonObject ||
            value instanceof JsonArray ||
            value instanceof String ||
            value instanceof Number ||
            value instanceof Boolean
        ) {
            return value;
        }
        Object stringForm = JsonLeaves.stringForm(value);
        if (stringForm != JsonLeaves.UNHANDLED) {
            return stringForm;
        }
        if (value instanceof Optional<?> opt) {
            return opt.isPresent()
                ? normalize(opt.get(), context, depth + 1)
                : null;
        }
        if (value instanceof OptionalInt oi) {
            return oi.isPresent() ? oi.getAsInt() : null;
        }
        if (value instanceof OptionalLong ol) {
            return ol.isPresent() ? ol.getAsLong() : null;
        }
        if (value instanceof OptionalDouble od) {
            return od.isPresent() ? od.getAsDouble() : null;
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
            throw new IllegalArgumentException(
                "JSON value nesting too deep (max " + MAX_DEPTH + " levels)"
            );
        }
        if (value instanceof JsonObject object) {
            context.enter(object);
            try {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : object.entries()) {
                    copy.put(
                        entry.getKey(),
                        deepCopy(entry.getValue(), context, depth + 1)
                    );
                }
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

    private static JsonObject normalizeBean(
        Object value,
        Context context,
        int depth
    ) {
        return withCycleGuard(context, value, () -> {
            BeanPlan plan = BeanIntrospector.plan(value.getClass());
            JsonObject object = JsonUtils.object();
            for (BeanProperty property : plan.properties()) {
                object.put(
                    property.name(),
                    normalize(property.read(value), context, depth + 1)
                );
            }
            return object;
        });
    }

    private static JsonArray normalizeArray(
        Object array,
        Context context,
        int depth
    ) {
        return withCycleGuard(context, array, () -> {
            JsonArray result = JsonUtils.array();
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                result.add(normalize(Array.get(array, i), context, depth + 1));
            }
            return result;
        });
    }

    private static JsonArray normalizeIterable(
        Iterable<?> iterable,
        Context context,
        int depth
    ) {
        return withCycleGuard(context, iterable, () -> {
            JsonArray result = JsonUtils.array();
            for (Object item : iterable) {
                result.add(normalize(item, context, depth + 1));
            }
            return result;
        });
    }

    private static JsonObject normalizeMap(
        Map<?, ?> map,
        Context context,
        int depth
    ) {
        return withCycleGuard(context, map, () -> {
            JsonObject result = JsonUtils.object();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException(
                        "Cannot serialize a Map with null keys to JSON"
                    );
                }
                result.put(
                    String.valueOf(key),
                    normalize(entry.getValue(), context, depth + 1)
                );
            }
            return result;
        });
    }

    /**
     * Runs {@code work} with {@code guard} registered as active in
     * {@code context}, releasing it in a finally block so the cycle guard
     * is cleared even when the work throws.
     */
    private static <T> T withCycleGuard(
        Context context,
        Object guard,
        Supplier<T> work
    ) {
        context.enter(guard);
        try {
            return work.get();
        } finally {
            context.exit(guard);
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

        private final IdentityHashMap<Object, Boolean> active =
            new IdentityHashMap<>();

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
