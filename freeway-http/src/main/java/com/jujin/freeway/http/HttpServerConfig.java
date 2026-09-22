package com.jujin.freeway.http;

import java.time.Duration;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

/**
 * Server-level configuration — the transport declaration half of a server
 * (its sibling {@link HttpPipeline} declares the handling half). It reaches
 * every {@link HttpEngine} unchanged through {@link HttpEngine#start}; the
 * honor tiers for its fields (must honor / per-exchange policy / engine-private)
 * are stated with the engine contract on {@link HttpEngine#start}.
 *
 * @param host              bind address
 * @param port              listen port (0 selects an ephemeral port)
 * @param backlog           accept backlog (0 uses the platform default)
 * @param shutdownGrace     grace period for in-flight requests on shutdown
 * @param maxBodySize       maximum request body size in bytes — per-exchange
 *                          policy, state-shaped: engines push it into each
 *                          exchange (honor tiers on {@link HttpEngine#start})
 * @param readTimeout       socket read idle timeout (zero disables); applied
 *                          to request reads, TLS handshakes, HTTP/2 frames,
 *                          and keep-alive waits
 * @param maxConnections    maximum concurrent connections (0 = unlimited);
 *                          excess connections are rejected at accept time
 * @param writeTimeout      per-socket-write timeout (zero disables); a write
 *                          blocked longer than this closes the connection
 * @param compression       gzip response-compression policy — per-exchange
 *                          policy, wire-adjacent: executed by the engine's
 *                          output path over shared {@link Compression}
 *                          primitives (honor tiers on {@link HttpEngine#start})
 * @param receiveBufferSize desired SO_RCVBUF for accepted sockets
 *                          (0 = OS default)
 * @param sendBufferSize    desired SO_SNDBUF for accepted sockets
 *                          (0 = OS default)
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
    int sendBufferSize
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_BACKLOG = 0;
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(2);
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

        private static final SymbolSpec<Boolean> ENABLED =
            SymbolSpec.of(HttpConfigKeys.COMPRESSION_ENABLED, Boolean.class, null);
        private static final SymbolSpec<Integer> MIN_SIZE =
            SymbolSpec.of(HttpConfigKeys.COMPRESSION_MIN_SIZE, Integer.class, null);

        /** The gzip policy as {@code freeway.http.compression.*} answers it. */
        public static CompressionConfig from(SymbolSource symbols) {
            CompressionConfig cfg = DEFAULT;
            cfg = cfg.withEnabled(symbols.resolve(ENABLED.orDefault(cfg.enabled())));
            cfg = cfg.withMinSize(symbols.resolve(MIN_SIZE.orDefault(cfg.minSize())));
            return cfg;
        }

        /** Same policy with compression switched on or off. */
        public CompressionConfig withEnabled(boolean enabled) {
            return new CompressionConfig(enabled, minSize);
        }

        /** Same policy leaving responses below this size uncompressed. */
        public CompressionConfig withMinSize(int minSize) {
            return new CompressionConfig(enabled, minSize);
        }
    }

    /**
     * The HTTP module's defaults: what {@code freeway.http.*} declares when
     * nothing is configured (host/port/backlog/shutdown grace) plus the library
     * defaults for every other knob. This is the one place they are stated —
     * {@link #from(com.jujin.freeway.ioc.symbol.SymbolSource)} overlays keys
     * onto it, and nothing restates these values.
     */
    public static HttpServerConfig defaults() {
        return new HttpServerConfig(
            DEFAULT_HOST, DEFAULT_PORT, DEFAULT_BACKLOG, DEFAULT_SHUTDOWN_GRACE,
            DEFAULT_MAX_BODY_SIZE, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_CONNECTIONS,
            DEFAULT_WRITE_TIMEOUT, CompressionConfig.DEFAULT, 0, 0);
    }

    // ── Key declarations: name and type only ──
    //
    // No spec states a default: `from` below pins each one to the field it
    // overlays with {@link SymbolSpec#orDefault}, so a default is written once —
    // on the value it belongs to — and an absent or blank key keeps it.

    private static final SymbolSpec<String> HOST =
        SymbolSpec.of(HttpConfigKeys.SERVER_HOST, String.class, null);
    private static final SymbolSpec<Integer> PORT =
        SymbolSpec.of(HttpConfigKeys.SERVER_PORT, Integer.class, null);
    private static final SymbolSpec<Integer> BACKLOG =
        SymbolSpec.of(HttpConfigKeys.SERVER_BACKLOG, Integer.class, null);
    private static final SymbolSpec<Duration> SHUTDOWN_GRACE =
        SymbolSpec.of(HttpConfigKeys.SERVER_SHUTDOWN_GRACE, Duration.class, null);
    private static final SymbolSpec<Long> MAX_BODY_SIZE =
        SymbolSpec.of(HttpConfigKeys.MAX_BODY_SIZE, Long.class, null);
    private static final SymbolSpec<Duration> READ_TIMEOUT =
        SymbolSpec.of(HttpConfigKeys.SERVER_READ_TIMEOUT, Duration.class, null);
    private static final SymbolSpec<Integer> MAX_CONNECTIONS =
        SymbolSpec.of(HttpConfigKeys.SERVER_MAX_CONNECTIONS, Integer.class, null);
    private static final SymbolSpec<Duration> WRITE_TIMEOUT =
        SymbolSpec.of(HttpConfigKeys.SERVER_WRITE_TIMEOUT, Duration.class, null);
    private static final SymbolSpec<Integer> RECEIVE_BUFFER =
        SymbolSpec.of(HttpConfigKeys.SERVER_RECEIVE_BUFFER, Integer.class, null);
    private static final SymbolSpec<Integer> SEND_BUFFER =
        SymbolSpec.of(HttpConfigKeys.SERVER_SEND_BUFFER, Integer.class, null);

    /**
     * This configuration as {@code freeway.http.server.*} answers it: start from
     * {@link #defaults()} and overlay one key per knob, each line naming only
     * its key and its field and defaulting back to the value already there.
     *
     * <p>{@code freeway.http.h2.*} is not carried: the reset guard belongs to
     * the built-in engine — {@code HttpModule} reads those keys into
     * {@code FreewayHttpEngine.Wiring}; a standalone caller passes
     * {@code withH2Reset} directly.
     */
    public static HttpServerConfig from(SymbolSource symbols) {
        HttpServerConfig cfg = defaults();
        cfg = cfg.withHost(symbols.resolve(HOST.orDefault(cfg.host())));
        cfg = cfg.withPort(symbols.resolve(PORT.orDefault(cfg.port())));
        cfg = cfg.withBacklog(symbols.resolve(BACKLOG.orDefault(cfg.backlog())));
        cfg = cfg.withShutdownGrace(symbols.resolve(SHUTDOWN_GRACE.orDefault(cfg.shutdownGrace())));
        cfg = cfg.withMaxBodySize(symbols.resolve(MAX_BODY_SIZE.orDefault(cfg.maxBodySize())));
        cfg = cfg.withReadTimeout(symbols.resolve(READ_TIMEOUT.orDefault(cfg.readTimeout())));
        cfg = cfg.withMaxConnections(symbols.resolve(MAX_CONNECTIONS.orDefault(cfg.maxConnections())));
        cfg = cfg.withWriteTimeout(symbols.resolve(WRITE_TIMEOUT.orDefault(cfg.writeTimeout())));
        cfg = cfg.withCompression(CompressionConfig.from(symbols));
        cfg = cfg.withReceiveBufferSize(symbols.resolve(RECEIVE_BUFFER.orDefault(cfg.receiveBufferSize())));
        cfg = cfg.withSendBufferSize(symbols.resolve(SEND_BUFFER.orDefault(cfg.sendBufferSize())));
        return cfg;
    }

    /** Same configuration with {@link #host} replaced. */
    public HttpServerConfig withHost(String host) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #port} replaced. */
    public HttpServerConfig withPort(int port) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #backlog} replaced. */
    public HttpServerConfig withBacklog(int backlog) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #shutdownGrace} replaced. */
    public HttpServerConfig withShutdownGrace(Duration shutdownGrace) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #maxBodySize} replaced. */
    public HttpServerConfig withMaxBodySize(long maxBodySize) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #readTimeout} replaced. */
    public HttpServerConfig withReadTimeout(Duration readTimeout) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #maxConnections} replaced. */
    public HttpServerConfig withMaxConnections(int maxConnections) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #writeTimeout} replaced. */
    public HttpServerConfig withWriteTimeout(Duration writeTimeout) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #compression} replaced. */
    public HttpServerConfig withCompression(CompressionConfig compression) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #receiveBufferSize} replaced. */
    public HttpServerConfig withReceiveBufferSize(int receiveBufferSize) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }

    /** Same configuration with {@link #sendBufferSize} replaced. */
    public HttpServerConfig withSendBufferSize(int sendBufferSize) {
        return new HttpServerConfig(host, port, backlog, shutdownGrace,
            maxBodySize, readTimeout, maxConnections, writeTimeout, compression,
            receiveBufferSize, sendBufferSize);
    }
}
