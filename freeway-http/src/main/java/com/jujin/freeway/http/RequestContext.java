package com.jujin.freeway.http;

import java.time.Instant;
import java.util.Map;

/**
 * Carries per-request metadata including a unique correlation identifier,
 * start timestamp, security principal, and arbitrary key-value attributes.
 */
public interface RequestContext {
    /** Returns the unique correlation identifier for this request. */
    String correlationId();

    /** Returns the instant at which this request started processing. */
    Instant startTime();

    /** Returns the authenticated principal, or null if not authenticated. */
    Object principal();

    /** Associates a security principal with this request. */
    void setPrincipal(Object principal);

    /** Returns the value of an attribute stored under the given key, or null. */
    Object attribute(String key);

    /** Stores an arbitrary attribute for the lifetime of this request. */
    void setAttribute(String key, Object value);

    /** Returns a live mutable map of all attributes associated with this request. */
    Map<String, Object> attributes();

    /** Creates a new context with an auto-generated correlation ID. */
    static RequestContext create() { return create(null); }

    /** Creates a new context with the given correlation ID, or auto-generated if blank. */
    static RequestContext create(String correlationId) {
        var now = Instant.now();
        return correlationId != null && !correlationId.isBlank()
            ? new RequestContextDefault(correlationId, now)
            : new RequestContextDefault(null, now);
    }
}
