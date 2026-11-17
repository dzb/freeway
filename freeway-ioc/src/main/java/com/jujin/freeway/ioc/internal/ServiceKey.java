package com.jujin.freeway.ioc.internal;

import java.util.Objects;

record ServiceKey(Class<?> type, String id) {
    ServiceKey {
        Objects.requireNonNull(type, "type");
        id = normalize(id);
    }

    /** Trims an id and rejects null or blank values. */
    static String normalize(String id) {
        String value = Objects.requireNonNull(id, "id").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Service id must not be blank");
        }
        return value;
    }
}
