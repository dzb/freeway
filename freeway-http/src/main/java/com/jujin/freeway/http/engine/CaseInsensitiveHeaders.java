package com.jujin.freeway.http.engine;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Case-insensitive response-header store that preserves insertion order and
 * the case of the most recently written name.
 *
 * <p>Lookup, replacement, and removal ignore case, so framework code never
 * needs repeated {@code equalsIgnoreCase()} scans. Writing a name that
 * already exists (ignoring case) replaces the value and moves the entry to
 * the end of the insertion order, matching the previous
 * {@code remove + put} semantics.</p>
 */
final class CaseInsensitiveHeaders {

    private final LinkedHashMap<Key, String> values = new LinkedHashMap<>();

    void set(String name, String value) {
        values.remove(new Key(name));
        values.put(new Key(name), value);
    }

    /** Replaces the value of an existing header without moving it or
     *  changing its name case; no-op when the header is absent. */
    void setValueIfPresent(String name, String value) {
        Key key = new Key(name);
        if (values.containsKey(key)) {
            values.put(key, value);
        }
    }

    void remove(String name) {
        values.remove(new Key(name));
    }

    String get(String name) {
        return values.get(new Key(name));
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

    /** Unmodifiable snapshot of the entries in insertion order. */
    List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> list = new ArrayList<>(values.size());
        for (var entry : values.entrySet()) {
            list.add(new SimpleImmutableEntry<>(
                entry.getKey().name, entry.getValue()));
        }
        return Collections.unmodifiableList(list);
    }

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
