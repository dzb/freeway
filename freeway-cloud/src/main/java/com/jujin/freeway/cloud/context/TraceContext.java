package com.jujin.freeway.cloud.context;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Distributed tracing context: traceId / spanId / parentSpanId, encoded as the
 * W3C {@code traceparent} header ({@code 00-traceid-spanid-flags}) for
 * interoperability with OpenTelemetry and other W3C-compliant tracers.
 *
 * @param traceId      32 lowercase hex chars
 * @param spanId       16 lowercase hex chars
 * @param parentSpanId 16 lowercase hex chars, or {@code null} for a root span
 */
public record TraceContext(String traceId, String spanId, String parentSpanId) {

    public TraceContext {
        requireHex(traceId, 32, "traceId");
        requireHex(spanId, 16, "spanId");
        if (parentSpanId != null) {
            requireHex(parentSpanId, 16, "parentSpanId");
        }
    }

    /** Creates a root context with a fresh traceId and spanId. */
    public static TraceContext root() {
        return new TraceContext(randomHex(32), randomHex(16), null);
    }

    /** Creates a child span of this context: same traceId, new spanId, this spanId as parent. */
    public TraceContext child() {
        return new TraceContext(traceId, randomHex(16), spanId);
    }

    /** Encodes this context as a W3C {@code traceparent} header value. */
    public String toTraceparent() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /** Parses a W3C {@code traceparent} header value; empty if malformed or unsupported version. */
    public static Optional<TraceContext> fromTraceparent(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String[] parts = header.trim().split("-");
        if (parts.length != 4 || !"00".equals(parts[0])) {
            return Optional.empty();
        }
        try {
            requireHex(parts[1], 32, "traceId");
            requireHex(parts[2], 16, "spanId");
            requireHex(parts[3], 2, "flags");
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new TraceContext(parts[1].toLowerCase(Locale.ROOT),
            parts[2].toLowerCase(Locale.ROOT), null));
    }

    private static void requireHex(String value, int length, String name) {
        if (value == null || value.length() != length) {
            throw new IllegalArgumentException(
                name + " must be " + length + " hex chars, got: " + value);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                throw new IllegalArgumentException(name + " contains non-hex char: " + c);
            }
        }
    }

    private static String randomHex(int chars) {
        // ThreadLocalRandom: the LXM generators are not thread-safe, and
        // root()/child() run on every concurrent request thread — a shared
        // generator produced duplicate trace/span ids under load.
        byte[] bytes = new byte[chars / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
