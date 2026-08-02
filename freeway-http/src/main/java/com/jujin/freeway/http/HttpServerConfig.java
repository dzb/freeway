package com.jujin.freeway.http;

import java.time.Duration;

/**
 * Server-level configuration for the built-in HTTP engine.
 *
 * @param host              bind address
 * @param port              listen port (0 selects an ephemeral port)
 * @param backlog           accept backlog (0 uses the platform default)
 * @param socketBufferSize  per-connection output buffer size in bytes
 * @param shutdownGrace     grace period for in-flight requests on shutdown
 * @param maxBodySize       maximum request body size in bytes
 */
public record HttpServerConfig(
    String host,
    int port,
    int backlog,
    int socketBufferSize,
    Duration shutdownGrace,
    long maxBodySize
) {
    public static final int DEFAULT_SOCKET_BUFFER_SIZE = 1024;
    public static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024L; // 10MB

    public HttpServerConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        if (backlog < 0) {
            throw new IllegalArgumentException("backlog must be >= 0: " + backlog);
        }
        if (socketBufferSize < 256) {
            throw new IllegalArgumentException("socketBufferSize must be at least 256: " + socketBufferSize);
        }
        if (shutdownGrace == null || shutdownGrace.isNegative()) {
            throw new IllegalArgumentException(
                "shutdownGrace must be non-negative: " + shutdownGrace);
        }
        if (maxBodySize <= 0) {
            throw new IllegalArgumentException(
                "maxBodySize must be positive: " + maxBodySize);
        }
    }

    public HttpServerConfig(String host, int port, int backlog, Duration shutdownGrace) {
        this(host, port, backlog, DEFAULT_SOCKET_BUFFER_SIZE, shutdownGrace, DEFAULT_MAX_BODY_SIZE);
    }
}
