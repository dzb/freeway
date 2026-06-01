package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.MappedContributions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.PriorityQueue;

final class ExtensionHub {
    private final Map<Class<?>, ListPoint<?>> listPoints = new LinkedHashMap<>();
    private final Map<MapPointKey, MapPoint<?, ?>> mapPoints = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    <T> Contributions<T> contributeList(Class<T> pointType) {
        Objects.requireNonNull(pointType, "pointType");
        return (ListPoint<T>) listPoints.computeIfAbsent(pointType, ListPoint::new);
    }

    @SuppressWarnings("unchecked")
    <K, V> MappedContributions<K, V> contributeMap(Class<K> keyType, Class<V> valueType) {
        MapPointKey pointKey = new MapPointKey(keyType, valueType);
        return (MapPoint<K, V>) mapPoints.computeIfAbsent(pointKey, ignored -> new MapPoint<>(pointKey));
    }

    @SuppressWarnings("unchecked")
    <T> List<T> resolveList(Class<T> pointType) {
        Objects.requireNonNull(pointType, "pointType");
        ListPoint<T> point = (ListPoint<T>) listPoints.get(pointType);
        if (point == null) {
            return List.of();
        }
        return point.resolve();
    }

    @SuppressWarnings("unchecked")
    <K, V> Map<K, V> resolveMap(Class<K> keyType, Class<V> valueType) {
        MapPointKey pointKey = new MapPointKey(keyType, valueType);
        MapPoint<K, V> point = (MapPoint<K, V>) mapPoints.get(pointKey);
        if (point == null) {
            return Map.of();
        }
        return point.resolve();
    }

    private static List<Entry<?>> order(Class<?> pointType, List<? extends Entry<?>> entries) {
        Map<String, Entry<?>> byId = new LinkedHashMap<>();
        Map<Entry<?>, Integer> positions = new LinkedHashMap<>();
        int index = 0;
        for (Entry<?> entry : entries) {
            positions.put(entry, index++);
            if (entry.id() != null) {
                byId.put(entry.id(), entry);
            }
        }

        Map<Entry<?>, Set<Entry<?>>> outgoing = new LinkedHashMap<>();
        Map<Entry<?>, Integer> indegree = new LinkedHashMap<>();
        for (Entry<?> entry : entries) {
            outgoing.put(entry, new LinkedHashSet<>());
            indegree.put(entry, 0);
        }

        for (Entry<?> entry : entries) {
            for (String id : entry.afterIds()) {
                Entry<?> dependency = byId.get(id);
                if (dependency != null) {
                    addEdge(dependency, entry, outgoing, indegree);
                }
            }
            for (String id : entry.beforeIds()) {
                Entry<?> target = byId.get(id);
                if (target != null) {
                    addEdge(entry, target, outgoing, indegree);
                }
            }
        }

        List<Entry<?>> ordered = new ArrayList<>(entries.size());
        PriorityQueue<Entry<?>> ready = new PriorityQueue<>(Comparator.comparingInt(positions::get));
        for (Entry<?> entry : entries) {
            if (indegree.get(entry) == 0) {
                ready.add(entry);
            }
        }

        while (!ready.isEmpty()) {
            Entry<?> entry = ready.remove();
            ordered.add(entry);
            for (Entry<?> target : outgoing.get(entry)) {
                int degree = indegree.get(target) - 1;
                indegree.put(target, degree);
                if (degree == 0) {
                    ready.add(target);
                }
            }
        }

        if (ordered.size() != entries.size()) {
            throw new IllegalStateException(
                "Contribution order cycle detected for extension " + pointType.getName()
            );
        }
        return ordered;
    }

    private static void addEdge(
        Entry<?> from,
        Entry<?> to,
        Map<Entry<?>, Set<Entry<?>>> outgoing,
        Map<Entry<?>, Integer> indegree
    ) {
        if (from == to) {
            return;
        }
        if (outgoing.get(from).add(to)) {
            indegree.put(to, indegree.get(to) + 1);
        }
    }

    private static String normalizeRequiredId(String id) {
        return normalizeOptionalId(Objects.requireNonNull(id, "id"));
    }

    private static String normalizeOptionalId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Contribution id must not be blank");
        }
        return normalized;
    }

    private static final class ListPoint<T> implements Contributions<T> {
        private final Class<T> pointType;
        private final List<Entry<T>> entries = new ArrayList<>();
        private final Set<String> ids = new LinkedHashSet<>();

        private ListPoint(Class<T> pointType) {
            this.pointType = pointType;
        }

        @Override
        public void add(T value) {
            add(null, value);
        }

        @Override
        public Contribution add(String id, T value) {
            Objects.requireNonNull(value, "value");
            String normalizedId = normalizeOptionalId(id);
            if (normalizedId != null && !ids.add(normalizedId)) {
                throw new IllegalStateException(
                    "Duplicate contribution id " + normalizedId + " for extension " + pointType.getName()
                );
            }
            Entry<T> entry = new Entry<>(normalizedId, value);
            entries.add(entry);
            return entry;
        }

        @SuppressWarnings("unchecked")
        List<T> resolve() {
            if (entries.isEmpty()) {
                return List.of();
            }
            return order(pointType, entries).stream()
                .map(entry -> ((Entry<T>) entry).value())
                .toList();
        }
    }

    private static final class MapPoint<K, V> implements MappedContributions<K, V> {
        private final MapPointKey pointKey;
        private final Map<K, V> values = new LinkedHashMap<>();

        private MapPoint(MapPointKey pointKey) {
            this.pointKey = pointKey;
        }

        @Override
        public void put(K key, V value) {
            K normalizedKey = Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (values.containsKey(normalizedKey)) {
                throw new IllegalStateException(
                    "Duplicate mapped contribution key " + normalizedKey + " for extension " + pointKey
                );
            }
            values.put(normalizedKey, value);
        }

        @Override
        public void override(K key, V value) {
            K normalizedKey = Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (!values.containsKey(normalizedKey)) {
                throw new IllegalStateException(
                    "Missing mapped contribution key " + normalizedKey + " for extension " + pointKey
                );
            }
            values.put(normalizedKey, value);
        }

        Map<K, V> resolve() {
            if (values.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private record MapPointKey(Class<?> keyType, Class<?> valueType) {
        private MapPointKey {
            Objects.requireNonNull(keyType, "keyType");
            Objects.requireNonNull(valueType, "valueType");
        }

        @Override
        public String toString() {
            return "Map<" + keyType.getName() + ", " + valueType.getName() + ">";
        }
    }

    private static final class Entry<T> implements Contribution {
        private final String id;
        private final T value;
        private final List<String> beforeIds = new ArrayList<>();
        private final List<String> afterIds = new ArrayList<>();

        private Entry(String id, T value) {
            this.id = id;
            this.value = value;
        }

        String id() {
            return id;
        }

        T value() {
            return value;
        }

        List<String> beforeIds() {
            return beforeIds;
        }

        List<String> afterIds() {
            return afterIds;
        }

        @Override
        public Contribution before(String... ids) {
            addIds(beforeIds, ids);
            return this;
        }

        @Override
        public Contribution after(String... ids) {
            addIds(afterIds, ids);
            return this;
        }

        private static void addIds(List<String> target, String... ids) {
            Objects.requireNonNull(ids, "ids");
            for (String id : ids) {
                target.add(normalizeRequiredId(id));
            }
        }

    }
}
