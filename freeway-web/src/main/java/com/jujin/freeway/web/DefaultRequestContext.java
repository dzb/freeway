package com.jujin.freeway2.web;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class DefaultRequestContext implements RequestContext {
    private final String correlationId;
    private final Instant startTime;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile Object principal;

    DefaultRequestContext(String correlationId, Instant startTime) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.startTime = Objects.requireNonNull(startTime, "startTime");
    }

    @Override
    public String correlationId() {
        return correlationId;
    }

    @Override
    public Instant startTime() {
        return startTime;
    }

    @Override
    public Object principal() {
        return principal;
    }

    @Override
    public void setPrincipal(Object principal) {
        this.principal = principal;
    }

    @Override
    public Object attribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(Objects.requireNonNull(key, "key"), value);
        }
    }

    @Override
    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }
}
