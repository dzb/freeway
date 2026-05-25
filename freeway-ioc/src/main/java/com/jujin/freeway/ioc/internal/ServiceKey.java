package com.jujin.freeway2.ioc.internal;

import com.jujin.freeway2.ioc.ServiceId;
import java.util.Objects;

record ServiceKey(Class<?> type, ServiceId id) {
    ServiceKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
