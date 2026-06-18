package com.jujin.freeway.commons.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Maps {

    private Maps() {}

    /**
     * Recursively flattens nested maps and lists into a flat {@code Map<String, String>}
     * with {@code delimiter}-separated keys.
     */
    public static Map<String, String> flatten(Map<String, Object> source, String delimiter) {
        Map<String, String> target = new LinkedHashMap<>();
        flatten("", delimiter, source, target);
        return target;
    }

    private static void flatten(String prefix, String delimiter, Map<String, Object> source, Map<String, String> target) {
        source.forEach((key, value) -> flattenValue(childKey(prefix, delimiter, key), delimiter, value, target));
    }

    private static void flatten(String prefix, String delimiter, List<?> source, Map<String, String> target) {
        for (int i = 0; i < source.size(); i++) {
            flattenValue(prefix + delimiter + i, delimiter, source.get(i), target);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flattenValue(String key, String delimiter, Object value, Map<String, String> target) {
        if (value instanceof Map<?, ?> map) {
            flatten(key, delimiter, (Map<String, Object>) map, target);
        } else if (value instanceof List<?> list) {
            flatten(key, delimiter, list, target);
        } else if (value != null) {
            target.put(key, String.valueOf(value));
        }
    }

    private static String childKey(String prefix, String delimiter, String key) {
        return prefix.isEmpty() ? key : prefix + delimiter + key;
    }
}
