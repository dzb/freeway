package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.ExchangeHandler;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.HttpServerHandle;

/**
 * Built-in HTTP engine using virtual threads and synchronous socket I/O.
 * Provides HTTP/1.1 (keep-alive), WebSocket, HTTP/2 h2c/h2, and HTTPS.
 * Constructable directly without the IoC container.
 */
public final class FreewayHttpEngine implements HttpEngine {

    private static final Logger LOG = LoggerFactory.getLogger(FreewayHttpEngine.class);

    /** Bounded backoff between accept() retries after a transient IOException
     *  (EMFILE/ENOBUFS/EINTR): fixed 50 ms keeps the retry rate low while the
     *  condition persists. */
    private static final long ACCEPT_RETRY_BACKOFF_NANOS = 50_000_000L;

    /** Accept source seam for fault-injection tests. When set before
     *  {@link #start}, the acceptor uses it instead of the engine's own
     *  server socket; production leaves it null. Package-private on purpose —
     *  not part of the public API. */
    AcceptSource acceptSource;

    /** Supplies the next accepted connection. */
    @FunctionalInterface
    interface AcceptSource {
        SocketChannel accept() throws IOException;
    }

    /** Default {@link Options#withH2Reset(int, Duration)}: RST burst count (0 disables). */
    public static final int DEFAULT_H2_RESET_BURST_LIMIT = 200;
    /** Default {@link Options#withH2Reset(int, Duration)}: guard sliding window. */
    public static final Duration DEFAULT_H2_RESET_WINDOW = Duration.ofSeconds(10);

    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private volatile SSLContext sslContext;
    private final boolean http2OverSsl;
    private final SSLParameters sslParameters;
    private final Options.SslReload sslReload;
    private final Metrics metrics;
    private final int h2ResetBurstLimit;
    private final Duration h2ResetWindow;

    /**
     * Required collaborators for the engine: the JSON codec and coercer are
     * required, while metrics defaults to the noop sink. These are dependencies,
     * not caller-selected options.
     */
    public record Deps(
        JsonCodec jsonCodec,
        Coercer coercer,
        Metrics metrics
    ) {
        public Deps {
            jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
            coercer = Objects.requireNonNull(coercer, "coercer");
            metrics = metrics == null ? NoopMetrics.INSTANCE : metrics;
        }

        /** Required codecs with the noop metrics sink. */
        public static Deps defaults(JsonCodec jsonCodec, Coercer coercer) {
            return new Deps(jsonCodec, coercer, NoopMetrics.INSTANCE);
        }

        /** Metrics sink; {@code null} restores the noop implementation. */
        public Deps withMetrics(Metrics metrics) {
            return new Deps(jsonCodec, coercer, metrics);
        }
    }

    /**
     * Caller-selected transport and protocol options for the engine: TLS
     * material, HTTP/2-over-TLS, and the HTTP/2 reset guard. These are values
     * with production-safe defaults, not collaborators.
     */
    public record Options(
        SSLContext sslContext,
        boolean http2OverSsl,
        SSLParameters sslParameters,
        SslReload sslReload,
        int h2ResetBurstLimit,
        Duration h2ResetWindow
    ) {
        public Options {
            if (h2ResetBurstLimit < 0) {
                throw new IllegalArgumentException(
                    "h2ResetBurstLimit must be >= 0: " + h2ResetBurstLimit);
            }
            if (h2ResetWindow == null || h2ResetWindow.isNegative()) {
                throw new IllegalArgumentException(
                    "h2ResetWindow must be non-negative: " + h2ResetWindow);
            }
        }

        /** Plain HTTP, no TLS, no HTTP/2 over TLS, default reset guard. */
        public static Options defaults() {
            return new Options(null, false, null, null,
                DEFAULT_H2_RESET_BURST_LIMIT, DEFAULT_H2_RESET_WINDOW);
        }

        /** TLS termination, optionally negotiating HTTP/2 over ALPN. */
        public Options withSsl(SSLContext sslContext, boolean http2OverSsl) {
            return new Options(sslContext, http2OverSsl, sslParameters, sslReload,
                h2ResetBurstLimit, h2ResetWindow);
        }

        /** Per-socket TLS parameters (client auth, cipher/protocol narrowing). */
        public Options withSslParameters(SSLParameters sslParameters) {
            return new Options(sslContext, http2OverSsl, sslParameters, sslReload,
                h2ResetBurstLimit, h2ResetWindow);
        }

        /** The built-in HTTP/2 inbound-RST burst guard ({@code freeway.http.h2.*}:
         *  counts read from {@code HttpModule}, or set directly here). */
        public Options withH2Reset(int h2ResetBurstLimit, Duration h2ResetWindow) {
            return new Options(sslContext, http2OverSsl, sslParameters, sslReload,
                h2ResetBurstLimit, h2ResetWindow);
        }

        /** Certificate hot-reload inputs: watched material plus the builder
         *  that rebuilds a context from it. {@code null} — the default —
         *  disables reload. Requires {@link #withSsl}: a reloader without an
         *  initial context cannot serve HTTPS. */
        public Options withSslReload(SslReload sslReload) {
            return new Options(sslContext, http2OverSsl, sslParameters, sslReload,
                h2ResetBurstLimit, h2ResetWindow);
        }

        /** Inputs for the engine's own certificate hot reload: the watched
         *  keystore/truststore/SNI directory, the poll interval (watch events
         *  drive the same check), and the builder that produces a fresh
         *  context. The module reads the {@code freeway.http.ssl.*} keys and
         *  hands the parts down; the engine never touches config. */
        public record SslReload(
            Path keyStorePath,
            Path trustStorePath,
            Path sniDirectory,
            Duration reloadInterval,
            Supplier<SSLContext> contextBuilder
        ) {
            public SslReload {
                keyStorePath = Objects.requireNonNull(keyStorePath, "keyStorePath");
                reloadInterval = Objects.requireNonNull(reloadInterval, "reloadInterval");
                contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
                if (reloadInterval.isZero() || reloadInterval.isNegative()) {
                    throw new IllegalArgumentException(
                        "reloadInterval must be positive: " + reloadInterval);
                }
            }
        }
    }

    /** The engine assembled from collaborators and transport options. */
    public FreewayHttpEngine(Deps deps, Options options) {
        Objects.requireNonNull(deps, "deps");
        Objects.requireNonNull(options, "options");
        this.jsonCodec = deps.jsonCodec();
        this.coercer = deps.coercer();
        this.sslContext = options.sslContext();
        this.http2OverSsl = options.http2OverSsl();
        this.sslParameters = options.sslParameters();
        this.sslReload = options.sslReload();
        this.metrics = deps.metrics();
        this.h2ResetBurstLimit = options.h2ResetBurstLimit();
        this.h2ResetWindow = options.h2ResetWindow();
    }

    public SSLContext sslContext() { return sslContext; }
    public boolean http2OverSsl() { return http2OverSsl; }
    public SSLParameters sslParameters() { return sslParameters; }
    Metrics metrics() { return metrics; }

    /** The built-in HTTP/2 reset guard this engine runs with. */
    int h2ResetBurstLimit() { return h2ResetBurstLimit; }
    Duration h2ResetWindow() { return h2ResetWindow; }

    /** A context was wired (or later reloaded) into this engine, so the
     *  sockets it accepts are TLS. Read on every call: {@link #reload} can
     *  only add certificate material, never take it away. */
    @Override
    public boolean isSecure() {
        return sslContext != null;
    }

    /** Atomically swaps the SSL context for new connections (certificate
     *  rotation / hot reload). Existing connections keep their old context. */
    public void reload(SSLContext sslContext) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
    }

    @Override
    public HttpServerHandle start(HttpServerConfig config, ExchangeHandler handler)
        throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");

        // Certificate hot reload is this engine's own lifecycle: started here
        // (before the listener, so unwatchable material fails startup before
        // binding) and closed by the handle it rides out on.
        if (sslReload != null && sslContext == null) {
            throw new IllegalStateException(
                "Options.SslReload requires withSsl(...) — a hot reload without an"
                + " initial SSLContext cannot serve HTTPS");
        }
        SslReloader reloader = null;
        if (sslReload != null) {
            reloader = new SslReloader(this, sslReload.keyStorePath(),
                sslReload.trustStorePath(), sslReload.sniDirectory(),
                sslReload.reloadInterval(), sslReload.contextBuilder());
            try {
                reloader.start();
            } catch (RuntimeException e) {
                reloader.close();
                throw e;
            }
        }

        // Channel-based listener so accepted sockets expose their
        // SocketChannel — required for the sendfile fast path.
        var ss = ServerSocketChannel.open();
        try {
            ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ss.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
        } catch (IOException | RuntimeException e) {
            try { ss.close(); } catch (IOException ignored) {}
            if (reloader != null) reloader.close();
            throw e;
        }
        int port = ss.socket().getLocalPort();

        var finished = new AtomicBoolean();
        HttpMetrics httpMetrics = new HttpMetrics(metrics);
        var tracker = new ConnectionTracker(httpMetrics);
        Metrics.Counter rejected = httpMetrics.connectionsRejected();
        Metrics.Counter accepted = httpMetrics.connectionsTotal();
        var permits = config.maxConnections() > 0
            ? new Semaphore(config.maxConnections()) : null;

        // Single platform-thread acceptor, one virtual thread per connection.
        // Virtual threads are cheap, so each connection gets its own carrier that
        // parks when idle (blocking I/O). The acceptor itself stays on a platform
        // thread to avoid virtual-thread pinning during accept().
        AcceptSource source = acceptSource != null ? acceptSource : ss::accept;
        var acceptor = Thread.ofPlatform().name("freeway-http-acceptor").start(() -> {
            // Transient accept failures (EMFILE/ENOBUFS/EINTR) must not kill
            // the listener: retry with a bounded backoff so a temporary
            // condition cannot silently strand the OS backlog. Only shutdown
            // (finished, or the server socket closed) breaks the loop. The
            // first consecutive failure is logged at error, the rest at debug
            // to avoid flooding the log while the condition persists.
            boolean loggedAcceptFailure = false;
            while (!finished.get()) {
                try {
                    var socket = source.accept().socket();
                    loggedAcceptFailure = false;
                    if (permits != null && !permits.tryAcquire()) {
                        // Reject excess connections at accept time so a flood
                        // cannot exhaust fds/threads; the client sees an
                        // immediate close instead of a queued connection.
                        rejected.increment();
                        socket.close();
                        continue;
                    }
                    accepted.increment();
                    Thread.ofVirtual()
                        .name("http-" + socket.getRemoteSocketAddress())
                        .start(new HttpSession(socket, handler, jsonCodec, coercer, this,
                            config, tracker, permits));
                } catch (IOException e) {
                    // ServerSocketChannel.accept() throws ClosedChannelException
                    // / AsynchronousCloseException when the listener is closed
                    // (shutdown); those — or an already-closed channel — mean
                    // the acceptor's work is done. Everything else is treated
                    // as transient and retried.
                    if (finished.get() || !ss.isOpen()
                            || e instanceof ClosedChannelException
                            || e instanceof AsynchronousCloseException) {
                        break;
                    }
                    if (!loggedAcceptFailure) {
                        LOG.error("Accept failed (will retry)", e);
                        loggedAcceptFailure = true;
                    } else {
                        LOG.debug("Accept failed (will retry)", e);
                    }
                    LockSupport.parkNanos(ACCEPT_RETRY_BACKOFF_NANOS);
                }
            }
        });

        String scheme = sslContext != null ? "https" : "http";
        LOG.info("Freeway HTTP engine ({}) started on {}:{}", scheme, config.host(), port);
        return new HttpServerHandleImpl(ss.socket(), acceptor, reloader,
            config.shutdownGrace(), finished, tracker, config.host(), port);
    }
}
