package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.HttpServerConfig;

/**
 * Single source of truth for the response-framing combination rules:
 * gzip applicability and body suppression (HEAD / 204 / 205 / 304). The
 * chunked-vs-Content-Length framing stays in the context and writers, but
 * every shared rule is decided here so a change cannot diverge between
 * HTTP/1.1 and HTTP/2. Internal to the engine — not part of the public
 * application API.
 */
public final class ResponseFraming {

    private ResponseFraming() {}

    /** gzip applies to buffered bodies only on non-206, body-allowed,
     *  min-size, client-negotiated, compressible responses. */
    public static boolean shouldGzip(HttpServerConfig.CompressionConfig compression,
            int status, boolean bodyAllowed, long bodyLength,
            boolean acceptsGzip, boolean compressible) {
        return compression.enabled() && status != 206 && bodyAllowed
            && bodyLength >= compression.minSize()
            && acceptsGzip && compressible;
    }

    /** Streaming variant: the body length is unknown until consumed, so the
     *  min-size gate cannot apply. */
    public static boolean shouldGzipStream(
            HttpServerConfig.CompressionConfig compression,
            int status, boolean bodyAllowed,
            boolean acceptsGzip, boolean compressible) {
        return compression.enabled() && status != 206 && bodyAllowed
            && acceptsGzip && compressible;
    }

    /** File variant: same gates as the streaming path, plus body-allowed so
     *  a bodyless status (204/304) never advertises Content-Encoding. */
    public static boolean shouldGzipFile(
            HttpServerConfig.CompressionConfig compression,
            int status, boolean bodyAllowed,
            boolean acceptsGzip, boolean compressible) {
        return shouldGzipStream(compression, status, bodyAllowed,
            acceptsGzip, compressible);
    }

    /** True when the wire must not carry body bytes: HEAD requests and
     *  204/205/304 statuses (which {@code bodyAllowed} already excludes). */
    public static boolean suppressBodyBytes(boolean bodyAllowed, String method) {
        return !bodyAllowed || "HEAD".equalsIgnoreCase(method);
    }
}
