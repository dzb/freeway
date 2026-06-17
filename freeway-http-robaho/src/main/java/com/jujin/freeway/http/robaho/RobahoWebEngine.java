package com.jujin.freeway.http.robaho;

import com.jujin.freeway.commons.json.JsonCodec;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.websocket.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import robaho.net.httpserver.websockets.WebSocket;
import robaho.net.httpserver.websockets.WebSocketHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class RobahoWebEngine implements HttpEngine {
    private static final Logger LOG = LoggerFactory.getLogger(RobahoWebEngine.class);
    private final JsonCodec jsonCodec;
    private final Coercer coercer;

    public RobahoWebEngine(JsonCodec jsonCodec, Coercer coercer) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            RequestContext requestContext = HttpContext.createRequestContext(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            JdkHttpContext ctx = new JdkHttpContext(exchange, jsonCodec, coercer, requestContext);
            ctx.headerSet("X-Request-Id", requestContext.correlationId());
            if (WebSocketHandler.isWebsocketRequested(exchange.getRequestHeaders())) {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                WebSocketMatch match = handler.websocket(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), origin);
                if (match == null) {
                    LOG.warn("WebSocket upgrade rejected: no matching route for {} {}",
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath());
                    ctx.send(404, "Not Found");
                    return;
                }
                handleWebSocket(exchange, requestContext, match);
                return;
            }
            try {
                handler.handle(ctx);
            } catch (Exception ex) {
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                }
                throw new IOException("Web request handler failed", ex);
            }
        });
        server.start();
        LOG.info("Freeway web engine started on {}:{}", config.host(), server.getAddress().getPort());
        return new RobahoHandle(server, executor, config.shutdownGraceSeconds(), config.host());
    }

    private void handleWebSocket(HttpExchange exchange, RequestContext requestContext, WebSocketMatch match) throws IOException {
        exchange.getResponseHeaders().set("X-Request-Id", requestContext.correlationId());
        try {
            new WebSocketHandler() {
                @Override
                protected WebSocket openWebSocket(HttpExchange ex) {
                    return new RobahoWebSocketSession(ex, requestContext, match.endpoint(), match.pathVariables());
                }
            }.handle(exchange);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("WebSocket upgrade failed", e);
        }
    }

    private record RobahoHandle(
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
                LOG.info("Freeway web engine stopped");
            }
        }
    }
}
