package com.jujin.freeway.http;

import java.time.Instant;
import java.util.Map;

public interface RequestContext {
    String correlationId();

    Instant startTime();

    Object principal();

    void setPrincipal(Object principal);

    Object attribute(String key);

    void setAttribute(String key, Object value);

    Map<String, Object> attributes();

    static RequestContext create() {
        return new RequestContextDefault(java.util.UUID.randomUUID().toString().replace("-", ""), Instant.now());
    }

    static RequestContext create(String correlationId) {
        return new RequestContextDefault(correlationId, Instant.now());
    }
}
