package com.jujin.freeway.http;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Default {@link ExchangeMeta} implementation shared by HTTP exchanges
 * ({@link AbstractHttpContext}) and WebSocket sessions. Internal — not part
 * of the application API.
 */
public final class ExchangeMetaDefault implements ExchangeMeta {

    private volatile String correlationId;
    private final Instant startTime;
    private volatile Object principal;
    private volatile ConcurrentHashMap<String, Object> attributes;

    public ExchangeMetaDefault(String correlationId) {
        this.correlationId = correlationId != null && !correlationId.isBlank()
            ? correlationId : fastCorrelationId();
        this.startTime = Instant.now();
    }

    /** Replaces the correlation id for a reused exchange (keep-alive);
     *  blank input keeps the existing id. */
    public void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            this.correlationId = correlationId;
        }
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

    /**
     * 32-char lowercase hex id generated without the UUID machinery: 128
     * bits from {@link ThreadLocalRandom} give ~2^-64 collision odds between
     * exchanges, and the path allocates only the scratch + the id string.
     */
    private static String fastCorrelationId() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
