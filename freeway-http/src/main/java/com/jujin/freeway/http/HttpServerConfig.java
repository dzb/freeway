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
 * @param readTimeout       socket read idle timeout (zero disables); applied
 *                          to request reads, TLS handshakes, HTTP/2 frames,
 *                          and keep-alive waits
 * @param maxConnections    maximum concurrent connections (0 = unlimited);
 *                          excess connections are rejected at accept time
 * @param writeTimeout      per-socket-write timeout (zero disables); a write
 *                          blocked longer than this closes the connection
 * @param compression       gzip response-compression policy
 * @param receiveBufferSize desired SO_RCVBUF for accepted sockets
 *                          (0 = OS default)
 * @param sendBufferSize    desired SO_SNDBUF for accepted sockets
 *                          (0 = OS default)
 */
public record HttpServerConfig(
    String host,
    int port,
    int backlog,
    int socketBufferSize,
    Duration shutdownGrace,
    long maxBodySize,
    Duration readTimeout,
    int maxConnections,
    Duration writeTimeout,
    CompressionConfig compression,
    int receiveBufferSize,
    int sendBufferSize
) {
    public static final int DEFAULT_SOCKET_BUFFER_SIZE = 1024;
    public static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024L; // 10MB
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_CONNECTIONS = 0;

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
        if (readTimeout == null || readTimeout.isNegative()) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
        if (maxConnections < 0) {
            throw new IllegalArgumentException(
                "maxConnections must be >= 0: " + maxConnections);
        }
        if (writeTimeout == null || writeTimeout.isNegative()) {
            writeTimeout = DEFAULT_WRITE_TIMEOUT;
        }
        if (compression == null) {
            compression = CompressionConfig.DEFAULT;
        }
        if (receiveBufferSize < 0 || sendBufferSize < 0) {
            throw new IllegalArgumentException(
                "socket buffer sizes must be >= 0");
        }
    }

    /** gzip response-compression policy. */
    public record CompressionConfig(boolean enabled, int minSize) {
        public static final CompressionConfig DEFAULT =
            new CompressionConfig(true, 256);

        public CompressionConfig {
            if (minSize < 0) minSize = 256;
        }
    }

    public HttpServerConfig(String host, int port, int backlog,
                            int socketBufferSize, Duration shutdownGrace,
                            long maxBodySize, Duration readTimeout,
                            int maxConnections) {
        this(host, port, backlog, socketBufferSize, shutdownGrace, maxBodySize,
            readTimeout, maxConnections, DEFAULT_WRITE_TIMEOUT,
            CompressionConfig.DEFAULT, 0, 0);
    }

    public HttpServerConfig(String host, int port, int backlog,
                            int socketBufferSize, Duration shutdownGrace,
                            long maxBodySize, Duration readTimeout,
                            int maxConnections, Duration writeTimeout) {
        this(host, port, backlog, socketBufferSize, shutdownGrace, maxBodySize,
            readTimeout, maxConnections, writeTimeout, CompressionConfig.DEFAULT,
            0, 0);
    }

    public HttpServerConfig(String host, int port, int backlog,
                            int socketBufferSize, Duration shutdownGrace,
                            long maxBodySize) {
        this(host, port, backlog, socketBufferSize, shutdownGrace, maxBodySize,
            DEFAULT_READ_TIMEOUT, DEFAULT_MAX_CONNECTIONS, DEFAULT_WRITE_TIMEOUT,
            CompressionConfig.DEFAULT, 0, 0);
    }

    public HttpServerConfig(String host, int port, int backlog, Duration shutdownGrace) {
        this(host, port, backlog, DEFAULT_SOCKET_BUFFER_SIZE, shutdownGrace,
            DEFAULT_MAX_BODY_SIZE, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_CONNECTIONS,
            DEFAULT_WRITE_TIMEOUT, CompressionConfig.DEFAULT, 0, 0);
    }
}
