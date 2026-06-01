package com.jujin.freeway.ioc.internal;

import java.util.Objects;

final class ServiceIds {
    private ServiceIds() {
    }

    static String normalize(String id) {
        String value = Objects.requireNonNull(id, "id").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Service id must not be blank");
        }
        return value;
    }
}
