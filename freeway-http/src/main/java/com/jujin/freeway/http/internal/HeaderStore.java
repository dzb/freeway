package com.jujin.freeway.http.internal;

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
 *
 * <p>Lives in {@code internal}: stable inside Freeway, with no promise to
 * applications. It is public only so the root-package policy object and the
 * engine writers can assemble it.</p>
 */
public final class HeaderStore {

    private final LinkedHashMap<Key, List<String>> values = new LinkedHashMap<>();

    /** Copies a parsed request's header map into a case-insensitive store,
     *  lowercasing every name.
     *
     *  <p>This is the one place a request's header names are canonicalized:
     *  HTTP/1.1 parsing lowercases them already, HTTP/2 field names arrive in
     *  whatever case the peer sent (the built-in engine keeps the first letter
     *  upper-case for display), and a map consumer such as
     *  {@code ctx.headers()} must not be able to tell which engine answered.
     *  Response headers are not copied through here — their case is preserved
     *  for the wire.</p> */
    public static HeaderStore copyOf(Map<String, List<String>> source) {
        HeaderStore headers = new HeaderStore();
        if (source != null) {
            source.forEach((name, values) -> {
                if (values != null) {
                    String canonical = name.toLowerCase(Locale.ROOT);
                    values.forEach(value -> headers.add(canonical, value));
                }
            });
        }
        return headers;
    }

    /** Replaces every value for the name, moving it to the end of insertion
     *  order (matching the previous remove + put semantics). */
    public void set(String name, String value) {
        values.remove(new Key(name));
        List<String> list = new ArrayList<>(1);
        list.add(value);
        values.put(new Key(name), list);
    }

    /** Appends a value to the name, preserving any existing values and their
     *  relative order. Used for repeated fields such as {@code Set-Cookie}. */
    public void add(String name, String value) {
        values.computeIfAbsent(new Key(name), k -> new ArrayList<>(2))
            .add(value);
    }

    public void remove(String name) {
        values.remove(new Key(name));
    }

    /** First value for the name, or null when absent. */
    public String get(String name) {
        List<String> list = values.get(new Key(name));
        return (list == null || list.isEmpty()) ? null : list.getFirst();
    }

    public boolean contains(String name) {
        return values.containsKey(new Key(name));
    }

    public void clear() {
        values.clear();
    }

    /** Unmodifiable snapshot of the entries in insertion order, flattened so
     *  a multi-valued header becomes one entry per value. */
    public List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (var entry : values.entrySet()) {
            for (String value : entry.getValue()) {
                list.add(new SimpleImmutableEntry<>(entry.getKey().name, value));
            }
        }
        return List.copyOf(list);
    }

    /** Unmodifiable, insertion-ordered view of all headers as a map. */
    public Map<String, List<String>> asMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            map.put(entry.getKey().name, List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    /** Key equality is case-insensitive for ASCII header names; header names
     *  are validated as RFC 7230 tokens by {@code HttpExchange}, so the
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
