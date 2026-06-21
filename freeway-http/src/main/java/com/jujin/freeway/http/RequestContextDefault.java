package com.jujin.freeway.http;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RequestContextDefault implements RequestContext {

    private final String correlationId;
    private final Instant startTime;
    private final ConcurrentHashMap<String, Object> attributes =
        new ConcurrentHashMap<>();
    private volatile Object principal;

    public RequestContextDefault(String correlationId, Instant startTime) {
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString().replace("-", "");
        }
        this.correlationId = correlationId;
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
        Objects.requireNonNull(key, "key");
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @Override
    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }
}
