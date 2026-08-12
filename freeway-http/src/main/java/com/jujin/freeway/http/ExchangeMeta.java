package com.jujin.freeway.http;

import java.time.Instant;
import java.util.Map;

/**
 * Metadata of one exchange, shared by HTTP exchanges ({@link HttpContext})
 * and upgraded connections ({@link com.jujin.freeway.http.websocket.WebSocketSession}):
 * a correlation id, the start time, a security principal, and arbitrary
 * cross-cutting attributes.
 */
public interface ExchangeMeta {

    /** Returns the unique correlation identifier for this exchange. */
    String correlationId();

    /** Returns the instant at which this exchange started. */
    Instant startTime();

    /** Returns the authenticated principal, or null if not authenticated. */
    Object principal();

    /** Associates a security principal with this exchange. */
    void setPrincipal(Object principal);

    /** Returns the value of an attribute stored under the given key, or null. */
    Object attribute(String key);

    /** Stores an arbitrary attribute for the lifetime of this exchange. */
    void setAttribute(String key, Object value);

    /** Returns an immutable snapshot of all attributes. */
    Map<String, Object> attributes();
}
