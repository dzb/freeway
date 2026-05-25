package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.ServiceId;
import java.util.Objects;

record ServiceKey(Class<?> type, ServiceId id) {
    ServiceKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
