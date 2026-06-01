package com.jujin.freeway.ioc.internal;

import java.util.Objects;

record ServiceKey(Class<?> type, String id) {
    ServiceKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
