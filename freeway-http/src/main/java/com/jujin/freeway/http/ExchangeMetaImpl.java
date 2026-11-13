package com.jujin.freeway.http;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The default {@link ExchangeMeta} implementation: the per-exchange state
 * (correlation id, start time, principal, attributes) that
 * {@link HttpExchangeSupport} and WebSocket session supports embed, one
 * instance per request (or per WebSocket connection lifetime). The exposed
 * state methods are specified by {@link ExchangeMeta}.
 */
public final class ExchangeMetaImpl implements ExchangeMeta {

    private volatile String correlationId;
    private volatile Instant startTime;
    private volatile Object principal;
    private volatile ConcurrentHashMap<String, Object> attributes;

    public ExchangeMetaImpl(String correlationId) {
        this.correlationId = correlationId != null && !correlationId.isBlank()
            ? correlationId : fastCorrelationId();
        this.startTime = Instant.now();
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
