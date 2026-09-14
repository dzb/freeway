package com.jujin.freeway.http;

import java.time.Duration;

/**
 * Server-level configuration for the built-in HTTP engine.
 *
 * @param host              bind address
 * @param port              listen port (0 selects an ephemeral port)
 * @param backlog           accept backlog (0 uses the platform default)
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
 * @param h2ResetBurstLimit HTTP/2 inbound-RST burst guard: cancels arriving
 *                          before the server responded, beyond this count
 *                          within {@code h2ResetWindow}, trip the connection
 *                          with GOAWAY(ENHANCE_YOUR_CALM) (0 disables)
 * @param h2ResetWindow     sliding window for the reset burst guard
 */
public record HttpServerConfig(
    String host,
    int port,
    int backlog,
    Duration shutdownGrace,
    long maxBodySize,
    Duration readTimeout,
    int maxConnections,
    Duration writeTimeout,
    CompressionConfig compression,
    int receiveBufferSize,
    int sendBufferSize,
    int h2ResetBurstLimit,
    Duration h2ResetWindow
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_BACKLOG = 0;
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(2);
    public static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024L; // 10MB
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_CONNECTIONS = 0;
    public static final int DEFAULT_H2_RESET_BURST_LIMIT = 200;
    public static final Duration DEFAULT_H2_RESET_WINDOW = Duration.ofSeconds(10);

    public HttpServerConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        if (backlog < 0) {
            throw new IllegalArgumentException("backlog must be >= 0: " + backlog);
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
            throw new IllegalArgumentException("readTimeout must be non-negative");
        }
        if (maxConnections < 0) {
            throw new IllegalArgumentException(
                "maxConnections must be >= 0: " + maxConnections);
        }
        if (writeTimeout == null || writeTimeout.isNegative()) {
            throw new IllegalArgumentException("writeTimeout must be non-negative");
        }
        if (compression == null) {
            compression = CompressionConfig.DEFAULT;
        }
        if (receiveBufferSize < 0 || sendBufferSize < 0) {
            throw new IllegalArgumentException(
                "socket buffer sizes must be >= 0");
        }
        if (h2ResetBurstLimit < 0) {
            throw new IllegalArgumentException(
                "h2ResetBurstLimit must be >= 0: " + h2ResetBurstLimit);
        }
        if (h2ResetWindow == null || h2ResetWindow.isNegative()) {
            throw new IllegalArgumentException(
                "h2ResetWindow must be non-negative: " + h2ResetWindow);
        }
    }

    /** gzip response-compression policy. */
    public record CompressionConfig(boolean enabled, int minSize) {
        public static final CompressionConfig DEFAULT =
            new CompressionConfig(true, 256);

        public CompressionConfig {
            if (minSize < 0) {
                throw new IllegalArgumentException("compression minSize must be >= 0: " + minSize);
            }
        }
    }

    /**
     * The HTTP module's defaults: what {@code freeway.http.*} declares when
     * nothing is configured (host/port/backlog/shutdown grace) plus the library
     * defaults for every other knob. This is the one place they are stated —
     * the config keys and {@link WebServerBuilder} read them from here.
     */
    public static HttpServerConfig defaults() {
        return new HttpServerConfig(
            DEFAULT_HOST, DEFAULT_PORT, DEFAULT_BACKLOG, DEFAULT_SHUTDOWN_GRACE,
            DEFAULT_MAX_BODY_SIZE, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_CONNECTIONS,
            DEFAULT_WRITE_TIMEOUT, CompressionConfig.DEFAULT, 0, 0,
            DEFAULT_H2_RESET_BURST_LIMIT, DEFAULT_H2_RESET_WINDOW);
    }

    /** Same configuration with {@link #host} replaced. */
    public HttpServerConfig withHost(String host) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #port} replaced. */
    public HttpServerConfig withPort(int port) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #backlog} replaced. */
    public HttpServerConfig withBacklog(int backlog) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #shutdownGrace} replaced. */
    public HttpServerConfig withShutdownGrace(Duration shutdownGrace) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #maxBodySize} replaced. */
    public HttpServerConfig withMaxBodySize(long maxBodySize) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #readTimeout} replaced. */
    public HttpServerConfig withReadTimeout(Duration readTimeout) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #maxConnections} replaced. */
    public HttpServerConfig withMaxConnections(int maxConnections) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #writeTimeout} replaced. */
    public HttpServerConfig withWriteTimeout(Duration writeTimeout) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #compression} replaced. */
    public HttpServerConfig withCompression(CompressionConfig compression) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #receiveBufferSize} replaced. */
    public HttpServerConfig withReceiveBufferSize(int receiveBufferSize) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #sendBufferSize} replaced. */
    public HttpServerConfig withSendBufferSize(int sendBufferSize) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #h2ResetBurstLimit} replaced. */
    public HttpServerConfig withH2ResetBurstLimit(int h2ResetBurstLimit) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }

    /** Same configuration with {@link #h2ResetWindow} replaced. */
    public HttpServerConfig withH2ResetWindow(Duration h2ResetWindow) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize, h2ResetBurstLimit, h2ResetWindow);
    }
}
