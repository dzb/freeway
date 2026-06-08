package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.ExtensionPoint;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * JDK-proxy based extension point implementation.
 * <p>
 * Stores contributed entries, applies {@code before/after} topological ordering,
 * and exposes results via {@link ExtensionPoint#all()}.
 */
final class ExtensionProxy implements InvocationHandler {

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private final Class<?> pointType;

    private ExtensionProxy(Class<?> pointType) {
        this.pointType = pointType;
    }

    static ExtensionProxy forPoint(Class<?> pointType) {
        return new ExtensionProxy(pointType);
    }

    @SuppressWarnings("unchecked")
    <E> E proxy(Class<E> pointType) {
        return (E) Proxy.newProxyInstance(
            pointType.getClassLoader(), new Class<?>[]{pointType}, this);
    }

    Contribution add(String id, Object value) {
        Objects.requireNonNull(value, "value");
        String normalizedId = normalizeOptionalId(id);
        if (normalizedId != null && !ids.add(normalizedId)) {
            throw new IllegalStateException(
                "Duplicate contribution id " + normalizedId + " for extension " + pointType.getName());
        }
        Entry entry = new Entry(normalizedId, value);
        entries.add(entry);
        return entry;
    }

    @SuppressWarnings("unchecked")
    <V> List<V> resolveAll() {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Entry> ordered = order();
        return (List<V>) ordered.stream().map(Entry::value).toList();
    }

    // ---- InvocationHandler ----

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("all") && method.getParameterCount() == 0) {
            return resolveAll();
        }
        return switch (method.getName()) {
            case "toString" -> "ExtensionPoint[" + pointType.getSimpleName() + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                "ExtensionPoint point " + pointType.getName() + " only supports all()");
        };
    }

    // ---- Resolution ----

    @SuppressWarnings("unchecked")
    private List<Entry> order() {
        if (entries.stream().allMatch(e -> e.afterIds.isEmpty() && e.beforeIds.isEmpty())) {
            return List.copyOf(entries);
        }

        Map<String, Entry> byId = new LinkedHashMap<>();
        Map<Entry, Integer> positions = new LinkedHashMap<>();
        int index = 0;
        for (Entry entry : entries) {
            positions.put(entry, index++);
            if (entry.id != null) {
                byId.put(entry.id, entry);
            }
        }

        Map<Entry, Set<Entry>> outgoing = new LinkedHashMap<>();
        Map<Entry, Integer> indegree = new LinkedHashMap<>();
        for (Entry entry : entries) {
            outgoing.put(entry, new LinkedHashSet<>());
            indegree.put(entry, 0);
        }

        for (Entry entry : entries) {
            for (String id : entry.afterIds) {
                Entry dep = byId.get(id);
                if (dep != null) addEdge(dep, entry, outgoing, indegree);
            }
            for (String id : entry.beforeIds) {
                Entry target = byId.get(id);
                if (target != null) addEdge(entry, target, outgoing, indegree);
            }
        }

        List<Entry> ordered = new ArrayList<>(entries.size());
        PriorityQueue<Entry> ready = new PriorityQueue<>(Comparator.comparingInt(positions::get));
        for (Entry entry : entries) {
            if (indegree.get(entry) == 0) ready.add(entry);
        }

        while (!ready.isEmpty()) {
            Entry entry = ready.remove();
            ordered.add(entry);
            for (Entry target : outgoing.get(entry)) {
                int deg = indegree.get(target) - 1;
                indegree.put(target, deg);
                if (deg == 0) ready.add(target);
            }
        }

        if (ordered.size() != entries.size()) {
            throw new IllegalStateException(
                "Contribution order cycle detected for extension " + pointType.getName());
        }
        return ordered;
    }

    private static void addEdge(Entry from, Entry to,
            Map<Entry, Set<Entry>> outgoing, Map<Entry, Integer> indegree) {
        if (from == to) return;
        if (outgoing.get(from).add(to)) indegree.put(to, indegree.get(to) + 1);
    }

    private static String normalizeOptionalId(String id) {
        if (id == null) return null;
        String v = id.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Contribution id must not be blank");
        return v;
    }

    // ---- Entry + Contribution ----

    private static final class Entry implements Contribution {
        final String id;
        final Object value;
        final List<String> beforeIds = new ArrayList<>();
        final List<String> afterIds = new ArrayList<>();

        Entry(String id, Object value) {
            this.id = id;
            this.value = value;
        }

        Object value() { return value; }

        @Override
        public Contribution before(String... ids) {
            for (String s : ids) beforeIds.add(Objects.requireNonNull(s, "id").trim());
            return this;
        }

        @Override
        public Contribution after(String... ids) {
            for (String s : ids) afterIds.add(Objects.requireNonNull(s, "id").trim());
            return this;
        }
    }
}
