package com.jujin.freeway.http.undertow;

import com.jujin.freeway.commons.json.JsonCodec;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.websocket.*;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;

final class UndertowWebEngine implements HttpEngine {
    private static final Logger LOG = LoggerFactory.getLogger(UndertowWebEngine.class);
    private final JsonCodec jsonCodec;
    private final Coercer coercer;

    public UndertowWebEngine(JsonCodec jsonCodec, Coercer coercer) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");

        HttpHandler root = exchange -> {
            if (exchange.isInIoThread()) {
                exchange.dispatch(() -> handle(exchange, handler));
                return;
            }
            handle(exchange, handler);
        };
        GracefulShutdownHandler gracefulShutdown = Handlers.gracefulShutdown(root);
        Undertow server = Undertow.builder()
            .addHttpListener(config.port(), config.host())
            .setHandler(gracefulShutdown)
            .build();
        server.start();
        LOG.info("Freeway undertow web engine started on {}:{}", config.host(), listenerPort(server));
        return new UndertowHandle(server, gracefulShutdown, config.shutdownGraceSeconds(), config.host());
    }

    private void handle(HttpServerExchange exchange, HttpRequestHandler handler) {
        try {
            dispatch(exchange, handler);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (Exception ex) {
            throw new RuntimeException("Undertow request dispatch failed", ex);
        }
    }

    private void dispatch(HttpServerExchange exchange, HttpRequestHandler handler) throws Exception {
        RequestContext requestContext = HttpContext.createRequestContext(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        exchange.getResponseHeaders().put(new HttpString("X-Request-Id"), requestContext.correlationId());
        if (isWebSocketRequest(exchange)) {
            String origin = exchange.getRequestHeaders().getFirst(Headers.ORIGIN);
            WebSocketMatch match = handler.websocket(method(exchange), path(exchange), origin);
            if (match == null) {
                ResponseCodeHandler.HANDLE_404.handleRequest(exchange);
                return;
            }
            handleWebSocket(exchange, requestContext, match);
            return;
        }

        exchange.startBlocking();
        UndertowHttpContext ctx = new UndertowHttpContext(exchange, jsonCodec, coercer, requestContext);
        try {
            handler.handle(ctx);
        } catch (Exception ex) {
            throw ex instanceof IOException io ? io : new IOException("Web request handler failed", ex);
        }
    }

    private void handleWebSocket(HttpServerExchange exchange, RequestContext requestContext, WebSocketMatch match) throws Exception {
        WebSocketConnectionCallback callback = (wsExchange, channel) -> {
            UndertowWebSocketSession session = new UndertowWebSocketSession(
                channel,
                requestContext,
                method(exchange),
                path(exchange),
                snapshotPathVariables(match.pathVariables()),
                snapshotQueryParameters(exchange),
                snapshotHeaders(exchange)
            );
            WebSocketListener listener;
            try {
                listener = match.endpoint().open(session);
            } catch (Exception ex) {
                throw new IllegalStateException("WebSocket endpoint failed", ex);
            }
            try {
                session.open(listener);
            } catch (Exception ex) {
                throw new IllegalStateException("WebSocket listener initialization failed", ex);
            }
        };
        WebSocketProtocolHandshakeHandler websocket = Handlers.websocket(callback);
        websocket.handleRequest(exchange);
    }

    private static boolean isWebSocketRequest(HttpServerExchange exchange) {
        String upgrade = exchange.getRequestHeaders().getFirst(Headers.UPGRADE);
        String connection = exchange.getRequestHeaders().getFirst(Headers.CONNECTION);
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade)
            && connection != null && connection.toLowerCase().contains("upgrade");
    }

    private static String method(HttpServerExchange exchange) {
        return exchange.getRequestMethod() != null ? exchange.getRequestMethod().toString() : "";
    }

    private static String path(HttpServerExchange exchange) {
        String relative = exchange.getRelativePath();
        return relative != null ? relative : "/";
    }

    private static Map<String, String> snapshotPathVariables(Map<String, String> vars) {
        return vars == null ? Map.of() : Map.copyOf(vars);
    }

    private static Map<String, List<String>> snapshotQueryParameters(HttpServerExchange exchange) {
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<String>> entry : exchange.getQueryParameters().entrySet()) {
            params.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(params);
    }

    private static Map<String, List<String>> snapshotHeaders(HttpServerExchange exchange) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        for (HttpString name : exchange.getRequestHeaders().getHeaderNames()) {
            List<String> values = new ArrayList<>();
            for (String value : exchange.getRequestHeaders().get(name)) {
                values.add(value);
            }
            headers.put(name.toString(), List.copyOf(values));
        }
        return Map.copyOf(headers);
    }

    private record UndertowHandle(
        Undertow server,
        GracefulShutdownHandler gracefulShutdown,
        int shutdownGraceSeconds,
        String host
    ) implements HttpServerHandle {
        @Override
        public int port() {
            return listenerPort(server);
        }

        @Override
        public void close() {
            try {
                gracefulShutdown.shutdown();
                gracefulShutdown.awaitShutdown(Math.max(0, shutdownGraceSeconds) * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                server.stop();
                LOG.info("Freeway undertow web engine stopped");
            }
        }
    }

    private static int listenerPort(Undertow server) {
        if (server.getListenerInfo().isEmpty()) {
            return -1;
        }
        SocketAddress address = server.getListenerInfo().get(0).getAddress();
        if (address instanceof InetSocketAddress inetSocketAddress) {
            return inetSocketAddress.getPort();
        }
        return -1;
    }
}
