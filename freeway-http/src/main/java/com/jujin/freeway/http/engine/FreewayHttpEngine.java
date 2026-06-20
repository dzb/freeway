package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpRequestHandler;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.HttpServerHandle;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in HTTP engine using virtual threads and synchronous socket I/O.
 * Provides HTTP/1.1 (keep-alive), WebSocket, HTTP/2 h2c/h2, and HTTPS.
 * Constructable directly without the IoC container.
 */
public final class FreewayHttpEngine implements HttpEngine {

    private static final Logger LOG = LoggerFactory.getLogger(FreewayHttpEngine.class);

    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private final SSLContext sslContext;
    private final boolean http2OverSsl;

    /** Plain HTTP engine. */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer) {
        this(jsonCodec, coercer, null, false);
    }

    /** HTTPS engine with optional HTTP/2 over TLS (ALPN). */
    public FreewayHttpEngine(JsonCodec jsonCodec, Coercer coercer,
                              SSLContext sslContext, boolean http2OverSsl) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.sslContext = sslContext;
        this.http2OverSsl = http2OverSsl;
    }

    public SSLContext sslContext() { return sslContext; }
    public boolean http2OverSsl() { return http2OverSsl; }

    @Override
    public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler)
        throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");

        var ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
        int port = ss.getLocalPort();

        var finished = new AtomicBoolean();

        var acceptor = new Thread(() -> {
                while (!finished.get()) {
                    try {
                        var socket = ss.accept();
                        Thread.ofVirtual().start(
                            new HttpSession(socket, handler, jsonCodec, coercer, this, config.socketBufferSize()));
                    } catch (IOException e) {
                        if (!finished.get()) LOG.error("Accept failed", e);
                        break;
                    }
                }
            }, "freeway-http-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        String scheme = sslContext != null ? "https" : "http";
        LOG.info("Freeway HTTP engine ({}) started on {}:{}", scheme, config.host(), port);
        return new ServerHandle(ss, acceptor,
            config.shutdownGrace(), finished, config.host(), port);
    }
}
