package com.jujin.freeway.http;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class RequestContextDefault implements RequestContext {

    private static final HexFormat HEX = HexFormat.of();

    private final String correlationId;
    private final Instant startTime;
    // Lazily allocated: the vast majority of requests never touch attributes,
    // and a fresh ConcurrentHashMap per request is wasted allocation on the
    // hot path.
    private volatile ConcurrentHashMap<String, Object> attributes;
    private volatile Object principal;

    public RequestContextDefault(String correlationId, Instant startTime) {
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = fastCorrelationId();
        }
        this.correlationId = correlationId;
        this.startTime = Objects.requireNonNull(startTime, "startTime");
    }

    /**
     * 32-char lowercase hex id, generated without the UUID machinery. A
     * correlation id is a tracing identifier, not a security token: 128 bits
     * from {@link ThreadLocalRandom} give ~2^-64 collision odds between
     * requests, and this path allocates only the 16-byte scratch + the id
     * string (vs UUID.randomUUID().toString().replace()'s SecureRandom call
     * and two intermediate strings per request).
     */
    private static String fastCorrelationId() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HEX.formatHex(bytes);
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
        var map = attributes;
        return map == null ? null : map.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        var map = attributes;
        if (map == null) {
            synchronized (this) {
                map = attributes;
                if (map == null) {
                    map = new ConcurrentHashMap<>();
                    attributes = map;
                }
            }
        }
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    @Override
    public Map<String, Object> attributes() {
        var map = attributes;
        return map == null ? Map.of() : Map.copyOf(map);
    }
}
