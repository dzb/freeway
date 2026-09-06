package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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

    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private volatile SSLContext sslContext;
    private final boolean http2OverSsl;
    private final SSLParameters sslParameters;
    private final Metrics metrics;

    /** Plain HTTP engine. */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer) {
        this(jsonCodec, coercer, null, false, null, NoopMetrics.INSTANCE);
    }

    /** Plain HTTP engine with metrics instrumentation. */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer, Metrics metrics) {
        this(jsonCodec, coercer, null, false, null,
            metrics == null ? NoopMetrics.INSTANCE : metrics);
    }

    /** HTTPS engine with optional HTTP/2 over TLS (ALPN). */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer,
                              SSLContext sslContext, boolean http2OverSsl) {
        this(jsonCodec, coercer, sslContext, http2OverSsl, null,
            NoopMetrics.INSTANCE);
    }

    /** HTTPS engine with optional HTTP/2 and per-socket TLS parameters. */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer,
                              SSLContext sslContext, boolean http2OverSsl,
                              SSLParameters sslParameters) {
        this(jsonCodec, coercer, sslContext, http2OverSsl, sslParameters,
            NoopMetrics.INSTANCE);
    }

    /** Full constructor: HTTPS/HTTP/2 options plus metrics instrumentation. */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer,
                              SSLContext sslContext, boolean http2OverSsl,
                              SSLParameters sslParameters, Metrics metrics) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.sslContext = sslContext;
        this.http2OverSsl = http2OverSsl;
        this.sslParameters = sslParameters;
        this.metrics = metrics == null ? NoopMetrics.INSTANCE : metrics;
    }

    public SSLContext sslContext() { return sslContext; }
    public boolean http2OverSsl() { return http2OverSsl; }
    public SSLParameters sslParameters() { return sslParameters; }
    Metrics metrics() { return metrics; }

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

        // Channel-based listener so accepted sockets expose their
        // SocketChannel — required for the sendfile fast path.
        var ss = ServerSocketChannel.open();
        try {
            ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ss.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
        } catch (IOException | RuntimeException e) {
            try { ss.close(); } catch (IOException ignored) {}
            throw e;
        }
        int port = ss.socket().getLocalPort();

        var finished = new AtomicBoolean();
        HttpMetrics httpMetrics = new HttpMetrics(metrics);
        var registry = new ConnectionRegistry(httpMetrics);
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
                            config, registry, permits));
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
        return new HttpServerHandleDefault(ss.socket(), acceptor,
            config.shutdownGrace(), finished, registry, config.host(), port);
    }
}
