package com.jujin.freeway.commons.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Maps {

    private Maps() {}

    /** Flattens with {@code "."} as the key delimiter. */
    public static Map<String, String> flatten(Map<String, Object> source) {
        return flatten(source, ".");
    }

    /**
     * Recursively flattens nested maps and lists into a flat {@code Map<String, String>}
     * with {@code delimiter}-separated keys.
     */
    public static Map<String, String> flatten(Map<String, Object> source, String delimiter) {
        Map<String, String> target = new LinkedHashMap<>();
        flatten("", delimiter, source, target, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return target;
    }

    private static void flatten(String prefix, String delimiter, Map<String, Object> source,
                                 Map<String, String> target, java.util.Set<Object> visited) {
        if (!visited.add(source)) return; // cycle guard
        source.forEach((key, value) ->
            flattenValue(childKey(prefix, delimiter, key), delimiter, value, target, visited));
    }

    private static void flatten(String prefix, String delimiter, List<?> source,
                                 Map<String, String> target, java.util.Set<Object> visited) {
        if (!visited.add(source)) return;
        for (int i = 0; i < source.size(); i++) {
            flattenValue(prefix + delimiter + i, delimiter, source.get(i), target, visited);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flattenValue(String key, String delimiter, Object value,
                                     Map<String, String> target, java.util.Set<Object> visited) {
        if (value instanceof Map<?, ?> map) {
            flatten(key, delimiter, (Map<String, Object>) map, target, visited);
        } else if (value instanceof List<?> list) {
            flatten(key, delimiter, list, target, visited);
        } else if (value != null) {
            target.put(key, String.valueOf(value));
        }
    }

    private static String childKey(String prefix, String delimiter, String key) {
        if (key == null) {
            throw new IllegalArgumentException("Map keys must not be null");
        }
        return prefix.isEmpty() ? key : prefix + delimiter + key;
    }
}
