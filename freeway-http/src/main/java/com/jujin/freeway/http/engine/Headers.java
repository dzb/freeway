package com.jujin.freeway.http.engine;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Case-insensitive, multi-valued header store shared by the request and
 * response paths. Preserves insertion order and the case of the most
 * recently written name.
 *
 * <p>Lookup, replacement, and removal ignore case, so framework code never
 * needs repeated {@code equalsIgnoreCase()} scans. Writing a name that
 * already exists (ignoring case) replaces the value and moves the entry to
 * the end of the insertion order, matching the previous
 * {@code remove + put} semantics.</p>
 */
final class Headers {

    private final LinkedHashMap<Key, List<String>> values = new LinkedHashMap<>();

    /** Copies an existing header map (for example a parsed request) into a
     *  case-insensitive store. */
    static Headers copyOf(Map<String, List<String>> source) {
        Headers headers = new Headers();
        if (source != null) {
            source.forEach((name, values) -> {
                if (values != null) {
                    values.forEach(value -> headers.add(name, value));
                }
            });
        }
        return headers;
    }

    /** Replaces every value for the name, moving it to the end of insertion
     *  order (matching the previous remove + put semantics). */
    void set(String name, String value) {
        values.remove(new Key(name));
        List<String> list = new ArrayList<>(1);
        list.add(value);
        values.put(new Key(name), list);
    }

    /** Appends a value to the name, preserving any existing values and their
     *  relative order. Used for repeated fields such as {@code Set-Cookie}. */
    void add(String name, String value) {
        values.computeIfAbsent(new Key(name), k -> new ArrayList<>(2))
            .add(value);
    }

    /** Replaces the value of an existing header without moving it or changing
     *  its name case; no-op when the header is absent. The name's value list
     *  is replaced with a single value. */
    void setValueIfPresent(String name, String value) {
        Key key = new Key(name);
        if (values.containsKey(key)) {
            List<String> list = new ArrayList<>(1);
            list.add(value);
            values.put(key, list);
        }
    }

    void remove(String name) {
        values.remove(new Key(name));
    }

    /** First value for the name, or null when absent. */
    String get(String name) {
        List<String> list = values.get(new Key(name));
        return (list == null || list.isEmpty()) ? null : list.getFirst();
    }

    /** Unmodifiable snapshot of all values for the name, or empty. */
    List<String> getAll(String name) {
        List<String> list = values.get(new Key(name));
        return list == null ? List.of() : List.copyOf(list);
    }

    boolean contains(String name) {
        return values.containsKey(new Key(name));
    }

    void clear() {
        values.clear();
    }

    int size() {
        return values.size();
    }

    /** Unmodifiable snapshot of the entries in insertion order, flattened so
     *  a multi-valued header becomes one entry per value. */
    List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (var entry : values.entrySet()) {
            for (String value : entry.getValue()) {
                list.add(new SimpleImmutableEntry<>(entry.getKey().name, value));
            }
        }
        return List.copyOf(list);
    }

    /** Unmodifiable, insertion-ordered view of all headers as a map. */
    Map<String, List<String>> asMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            map.put(entry.getKey().name, List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    /** Key equality is case-insensitive for ASCII header names; header names
     *  are validated as RFC 7230 tokens by {@code HttpContext}, so the
     *  lower-cased hash below is consistent with {@code equalsIgnoreCase}. */
    private record Key(String name) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && name.equalsIgnoreCase(other.name);
        }

        @Override
        public int hashCode() {
            return name.toLowerCase(Locale.ROOT).hashCode();
        }
    }
}
