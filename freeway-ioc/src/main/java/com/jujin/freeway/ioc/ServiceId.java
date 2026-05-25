package com.jujin.freeway2.ioc;

import java.util.Objects;

public record ServiceId(String value) {
    public ServiceId(String value) {
        this.value = Objects.requireNonNull(value, "value").trim();
        if (this.value.isEmpty()) {
            throw new IllegalArgumentException("Service id must not be blank");
        }
    }

    public static ServiceId of(String value) {
        return new ServiceId(value);
    }
}
