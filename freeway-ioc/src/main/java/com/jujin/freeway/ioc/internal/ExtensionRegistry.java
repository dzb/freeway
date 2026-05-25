package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.MappedContributions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExtensionRegistry {
    private final Map<Class<?>, List<Object>> listExtensions = new LinkedHashMap<>();
    private final Map<Class<?>, KeyedExtension<?>> keyedExtensions = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    <T> Contributions<T> contributions(Class<T> valueType) {
        return value -> listExtensions
            .computeIfAbsent(valueType, ignored -> new ArrayList<>())
            .add(value);
    }

    @SuppressWarnings("unchecked")
    <V> MappedContributions<V> mapped(Class<V> valueType) {
        return keyedExtension(valueType);
    }

    @SuppressWarnings("unchecked")
    <T> List<T> values(Class<T> valueType) {
        return (List<T>) List.copyOf(listExtensions.getOrDefault(valueType, List.of()));
    }

    <V> Map<String, V> mappedValues(Class<V> valueType) {
        return (Map<String, V>) keyedExtension(valueType).values();
    }

    @SuppressWarnings("unchecked")
    private <V> KeyedExtension<V> keyedExtension(Class<V> valueType) {
        return (KeyedExtension<V>) keyedExtensions.computeIfAbsent(valueType, ignored -> new KeyedExtension<>());
    }

    private static final class KeyedExtension<V> implements MappedContributions<V> {
        private final Map<String, V> values = new LinkedHashMap<>();

        @Override
        public void put(String key, V value) {
            values.put(key, value);
        }

        Map<String, V> values() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
