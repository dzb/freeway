package com.jujin.freeway.http.internal;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpRequestHandler;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.HttpServerHandle;
import com.jujin.freeway.http.RequestContext;

public final class JdkHttpEngine implements HttpEngine {

    private static final Logger LOG = LoggerFactory.getLogger(
        JdkHttpEngine.class
    );
    private final JsonCodec jsonCodec;
    private final Coercer coercer;

    public JdkHttpEngine(JsonCodec jsonCodec, Coercer coercer) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public JdkHandle start(HttpServerConfig config, HttpRequestHandler handler)
        throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.host(), config.port()),
            config.backlog()
        );
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            RequestContext requestContext = HttpContext.createRequestContext(
                exchange.getRequestHeaders().getFirst("X-Request-Id")
            );
            JdkHttpContext ctx = new JdkHttpContext(
                exchange,
                jsonCodec,
                coercer,
                requestContext
            );
            ctx.headerSet("X-Request-Id", requestContext.correlationId());
            if (isWebSocketUpgrade(exchange.getRequestHeaders())) {
                ctx.send(
                    426,
                    "WebSocket not supported by JDK engine; add freeway-http-robaho, freeway-http-undertow, or freeway-http-jetty to the classpath"
                );
                return;
            }
            try {
                handler.handle(ctx);
            } catch (Exception ex) {
                if (ex instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Web request handler failed", ex);
            }
        });
        server.start();
        LOG.info(
            "Freeway JDK web engine started on {}:{}",
            config.host(),
            server.getAddress().getPort()
        );
        return new JdkHandle(
            server,
            executor,
            config.shutdownGraceSeconds(),
            config.host()
        );
    }

    private static boolean isWebSocketUpgrade(Headers headers) {
        List<String> connection = headers.get("Connection");
        List<String> upgrade = headers.get("Upgrade");
        if (connection == null || upgrade == null) {
            return false;
        }
        boolean connectionUpgrade = connection
            .stream()
            .anyMatch(v ->
                List.of(v.split("\\s*,\\s*"))
                    .stream()
                    .anyMatch("upgrade"::equalsIgnoreCase)
            );
        boolean websocketUpgrade = upgrade
            .stream()
            .anyMatch("websocket"::equalsIgnoreCase);
        return connectionUpgrade && websocketUpgrade;
    }

    private record JdkHandle(
        HttpServer server,
        ExecutorService executor,
        int shutdownGraceSeconds,
        String host
    ) implements HttpServerHandle {
        @Override
        public int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            try {
                server.stop(shutdownGraceSeconds);
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
                LOG.info("Freeway JDK web engine stopped");
            }
        }
    }
}
