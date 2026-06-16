package com.jujin.freeway.http.jetty;

import com.jujin.freeway.commons.json.JsonCodec;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.websocket.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class JettyWebEngine implements HttpEngine {
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebEngine.class);
    private final JsonCodec jsonCodec;
    private final Coercer coercer;

    public JettyWebEngine(JsonCodec jsonCodec, Coercer coercer) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    @Override
    public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(config.host());
        connector.setPort(config.port());
        connector.setAcceptQueueSize(config.backlog());
        server.addConnector(connector);
        server.setStopTimeout(Math.max(0, config.shutdownGraceSeconds()) * 1000L);

        ServerWebSocketContainer webSocketContainer = ServerWebSocketContainer.ensure(server);
        GracefulHandler graceful = new GracefulHandler();
        graceful.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) throws Exception {
                RequestContext requestContext = HttpContext.createRequestContext(request.getHeaders().get("X-Request-Id"));
                response.getHeaders().put("X-Request-Id", requestContext.correlationId());
                if (isWebSocketRequest(request)) {
                    return handleWebSocket(request, response, callback, handler, requestContext, webSocketContainer);
                }
                JettyHttpContext ctx = new JettyHttpContext(request, response, jsonCodec, coercer, requestContext, callback);
                try {
                    handler.handle(ctx);
                } catch (Exception ex) {
                    response.setStatus(500);
                    response.getHeaders().put("Content-Type", "text/plain; charset=utf-8");
                    response.write(true, ByteBuffer.wrap("Internal Server Error".getBytes(java.nio.charset.StandardCharsets.UTF_8)), callback);
                    return true;
                }
                if (!ctx.responded()) {
                    callback.succeeded();
                }
                return true;
            }
        });
        server.setHandler(graceful);
        try {
            server.start();
        } catch (Exception ex) {
            throw new IOException("Unable to start Jetty server", ex);
        }

        int port = currentPort(server);
        LOG.info("Freeway jetty web engine started on {}:{}", config.host(), port);
        return new JettyHandle(server, graceful, config.shutdownGraceSeconds(), config.host(), port);
    }

    private boolean handleWebSocket(
        Request request,
        Response response,
        org.eclipse.jetty.util.Callback callback,
        HttpRequestHandler handler,
        RequestContext requestContext,
        ServerWebSocketContainer webSocketContainer
    ) {
        String method = method(request);
        String path = path(request);
        String origin = request.getHeaders().get("Origin");
        WebSocketMatch match = handler.websocket(method, path, origin);
        if (match == null) {
            response.setStatus(404);
            response.getHeaders().put("Content-Type", "text/plain; charset=utf-8");
            response.write(true, ByteBuffer.wrap("Not Found".getBytes(java.nio.charset.StandardCharsets.UTF_8)), callback);
            return true;
        }
        WebSocketCreator creator = (upgradeRequest, upgradeResponse, upgradeCallback) -> {
            upgradeResponse.getHeaders().put("X-Request-Id", requestContext.correlationId());
            return new JettyWebSocketBridge(
                match,
                requestContext,
                method,
                path,
                snapshotPathVariables(match.pathVariables()),
                snapshotQueryParameters(upgradeRequest),
                snapshotHeaders(upgradeRequest)
            );
        };
        try {
            if (!webSocketContainer.upgrade(creator, request, response, callback)) {
                response.setStatus(400);
                response.getHeaders().put("Content-Type", "text/plain; charset=utf-8");
                response.write(true, ByteBuffer.wrap("WebSocket upgrade rejected".getBytes(java.nio.charset.StandardCharsets.UTF_8)), callback);
            }
        } catch (Exception ex) {
            LOG.warn("Jetty websocket upgrade failed for {} {}", method, path, ex);
            response.setStatus(500);
            response.getHeaders().put("Content-Type", "text/plain; charset=utf-8");
            response.write(true, ByteBuffer.wrap("WebSocket upgrade failed".getBytes(java.nio.charset.StandardCharsets.UTF_8)), callback);
        }
        return true;
    }

    private static boolean isWebSocketRequest(Request request) {
        String upgrade = request.getHeaders().get("Upgrade");
        String connection = request.getHeaders().get("Connection");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade)
            && connection != null && connection.toLowerCase().contains("upgrade");
    }

    private static String method(Request request) {
        String method = request.getMethod();
        return method != null ? method : "";
    }

    private static String path(Request request) {
        String path = request.getHttpURI() != null ? request.getHttpURI().getPath() : null;
        return path != null ? path : "/";
    }

    private static Map<String, String> snapshotPathVariables(Map<String, String> vars) {
        return vars == null ? Map.of() : Map.copyOf(vars);
    }

    private static Map<String, List<String>> snapshotQueryParameters(Request request) {
        Fields fields = Request.extractQueryParameters(request);
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        for (Fields.Field field : fields) {
            params.put(field.getName(), List.copyOf(field.getValues()));
        }
        return Map.copyOf(params);
    }

    private static Map<String, List<String>> snapshotHeaders(Request request) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : request.getHeaders().getFieldNamesCollection()) {
            List<String> values = new ArrayList<>(request.getHeaders().getValuesList(name));
            headers.put(name, List.copyOf(values));
        }
        return Map.copyOf(headers);
    }

    private static int currentPort(Server server) {
        for (Connector connector : server.getConnectors()) {
            if (connector instanceof ServerConnector serverConnector) {
                int port = serverConnector.getLocalPort();
                if (port > 0) {
                    return port;
                }
            }
        }
        return -1;
    }

    private record JettyHandle(
        Server server,
        GracefulHandler graceful,
        int shutdownGraceSeconds,
        String host,
        int port
    ) implements HttpServerHandle {
        @Override
        public int port() {
            return port;
        }

        @Override
        public void close() {
            try {
                graceful.shutdown().get(Math.max(0, shutdownGraceSeconds), TimeUnit.SECONDS);
            } catch (Exception ex) {
                // fall through to stop
            } finally {
                try {
                    server.stop();
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to stop Jetty server", ex);
                }
                LOG.info("Freeway jetty web engine stopped");
            }
        }
    }

    public static final class JettyWebSocketBridge implements Session.Listener.AutoDemanding {
        private final WebSocketMatch match;
        private final RequestContext requestContext;
        private final String method;
        private final String path;
        private final Map<String, String> pathVariables;
        private final Map<String, List<String>> queryParams;
        private final Map<String, List<String>> headers;
        private volatile WebSocketListener appListener = WebSocketListener.NOOP;
        private volatile JettyWebSocketSession session;

        JettyWebSocketBridge(
            WebSocketMatch match,
            RequestContext requestContext,
            String method,
            String path,
            Map<String, String> pathVariables,
            Map<String, List<String>> queryParams,
            Map<String, List<String>> headers
        ) {
            this.match = Objects.requireNonNull(match, "match");
            this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
            this.method = Objects.requireNonNull(method, "method");
            this.path = Objects.requireNonNull(path, "path");
            this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
            this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public void onWebSocketOpen(Session session) {
            JettyWebSocketSession wsSession = new JettyWebSocketSession(
                session,
                requestContext,
                method,
                path,
                pathVariables,
                queryParams,
                headers
            );
            this.session = wsSession;
            try {
                appListener = match.endpoint().open(wsSession);
                if (appListener == null) {
                    appListener = WebSocketListener.NOOP;
                }
                appListener.onOpen(wsSession);
            } catch (Exception ex) {
                throw new IllegalStateException("WebSocket endpoint failed", ex);
            }
        }

        @Override
        public void onWebSocketText(String message) {
            try {
                appListener.onText(message);
            } catch (Exception ex) {
                onWebSocketError(ex);
                closeWithError(ex);
            }
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, org.eclipse.jetty.websocket.api.Callback callback) {
            try {
                byte[] data;
                if (payload == null) {
                    data = new byte[0];
                } else {
                    ByteBuffer copy = payload.slice();
                    data = new byte[copy.remaining()];
                    copy.get(data);
                }
                appListener.onBinary(data);
                callback.succeed();
            } catch (Exception ex) {
                onWebSocketError(ex);
                callback.fail(ex);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason, org.eclipse.jetty.websocket.api.Callback callback) {
            try {
                appListener.onClose(statusCode, reason, session != null && !session.localCloseRequested());
                callback.succeed();
            } catch (Exception ex) {
                callback.fail(ex);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            try {
                appListener.onError(cause);
            } catch (Exception ex) {
                LOG.warn("Jetty websocket listener failed while handling error", ex);
            }
        }

        private void closeWithError(Throwable cause) {
            if (session == null || !session.isOpen()) {
                return;
            }
            try {
                session.close(1011, cause != null && cause.getMessage() != null ? cause.getMessage() : "websocket error");
            } catch (Exception ex) {
                LOG.warn("Jetty websocket session failed to close after error", ex);
            }
        }
    }
}
