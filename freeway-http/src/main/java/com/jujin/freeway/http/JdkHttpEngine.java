package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link HttpEngine} implementation backed by {@link com.sun.net.httpserver.HttpServer}.
 * Suitable for lightweight scenarios where a minimal HTTP engine is sufficient.
 * <p>
 * This engine does NOT support WebSocket. For WebSocket-capable engines,
 * use {@code FreewayHttpEngine} or an external adapter (e.g. freeway-http-undertow).
 */
public final class JdkHttpEngine implements HttpEngine {
    private static final Logger LOG = LoggerFactory.getLogger(JdkHttpEngine.class);
    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors() * 4;

    private final JsonCodec jsonCodec;
    private final Coercer coercer;

    public JdkHttpEngine(JsonCodec jsonCodec, Coercer coercer) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler) throws IOException {
        var executor = Executors.newFixedThreadPool(POOL_SIZE);
        var server = HttpServer.create(
            new InetSocketAddress(config.host(), config.port()), config.backlog());
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var requestContext = HttpContext.createRequestContext(
                exchange.getRequestHeaders().getFirst("X-Request-Id"));
            var ctx = new JdkHttpContext(exchange, jsonCodec, coercer, requestContext);
            ctx.headerSet("X-Request-Id", requestContext.correlationId());
            try {
                handler.handle(ctx);
            } catch (Exception ex) {
                if (ex instanceof IOException) throw (IOException) ex;
                throw new IOException("request handler failed", ex);
            }
        });
        server.start();
        LOG.info("JDK HTTP engine started on {}:{}", config.host(), server.getAddress().getPort());
        return new JdkHandle(server, executor, config.shutdownGrace(), config.host());
    }

    private record JdkHandle(
        HttpServer server,
        java.util.concurrent.ExecutorService executor,
        Duration shutdownGrace,
        String host
    ) implements HttpServerHandle {
        @Override
        public int port() { return server.getAddress().getPort(); }

        @Override
        public void close() {
            try {
                server.stop((int) shutdownGrace.toSeconds());
            } finally {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                LOG.info("JDK HTTP engine stopped");
            }
        }
    }
}
