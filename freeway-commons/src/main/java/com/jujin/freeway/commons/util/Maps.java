package com.jujin.freeway.commons.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Map utility methods: flattening nested structures with cycle detection.
 */
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
        flatten("", delimiter, source, target, Collections.newSetFromMap(new IdentityHashMap<>()));
        return target;
    }

    private static void flatten(String prefix, String delimiter, Map<String, Object> source,
                                Map<String, String> target, Set<Object> visited) {
        // The set tracks the current ancestry path only (added on entry,
        // removed on exit), so shared subtrees reached under sibling keys are
        // still flattened and only true cycles — revisiting an ancestor — cut.
        if (!visited.add(source)) return; // cycle guard
        try {
            source.forEach((key, value) ->
                flattenValue(childKey(prefix, delimiter, key), delimiter, value, target, visited));
        } finally {
            visited.remove(source);
        }
    }

    private static void flatten(String prefix, String delimiter, List<?> source,
                                Map<String, String> target, Set<Object> visited) {
        if (!visited.add(source)) return; // cycle guard
        try {
            for (int i = 0; i < source.size(); i++) {
                flattenValue(prefix + delimiter + i, delimiter, source.get(i), target, visited);
            }
        } finally {
            visited.remove(source);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flattenValue(String key, String delimiter, Object value,
                                     Map<String, String> target, Set<Object> visited) {
        if (value instanceof Map<?, ?> map) {
            flatten(key, delimiter, (Map<String, Object>) map, target, visited);
        } else if (value instanceof List<?> list) {
            flatten(key, delimiter, list, target, visited);
        } else if (value != null) {
            if (target.put(key, String.valueOf(value)) != null) {
                throw new IllegalArgumentException(
                    "Duplicate flattened key '" + key + "'"
                );
            }
        }
    }

    private static String childKey(String prefix, String delimiter, String key) {
        if (key == null) {
            throw new IllegalArgumentException("Map keys must not be null");
        }
        return prefix.isEmpty() ? key : prefix + delimiter + key;
    }
}
