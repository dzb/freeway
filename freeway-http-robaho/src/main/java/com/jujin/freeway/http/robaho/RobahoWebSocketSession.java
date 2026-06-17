package com.jujin.freeway.http.robaho;

import com.jujin.freeway.http.*;
import com.jujin.freeway.http.websocket.*;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import robaho.net.httpserver.websockets.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RobahoWebSocketSession extends WebSocket implements WebSocketSession {
    private static final Logger LOG = LoggerFactory.getLogger(RobahoWebSocketSession.class);

    private final HttpExchange exchange;
    private final RequestContext requestContext;
    private final WebSocketEndpoint endpoint;
    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> headers;
    private final Object sendLock = new Object();
    private volatile WebSocketListener listener = WebSocketListener.NOOP;

    RobahoWebSocketSession(HttpExchange exchange, RequestContext requestContext, WebSocketEndpoint endpoint, Map<String, String> pathVariables) {
        super(exchange);
        this.exchange = exchange;
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.method = exchange.getRequestMethod();
        this.path = exchange.getRequestURI().getPath();
        this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
        this.queryParams = JdkHttpContext.parseQueryParams(exchange.getRequestURI().getRawQuery());
        this.headers = snapshotHeaders(exchange);
    }

    private final StringBuilder continuationBuffer = new StringBuilder();
    private boolean continuationIsText;

    @Override
    protected void onOpen() throws WebSocketException {
        try {
            WebSocketListener opened = endpoint.open(this);
            listener = opened != null ? opened : WebSocketListener.NOOP;
            listener.onOpen(this);
        } catch (Exception ex) {
            LOG.warn("WebSocket listener onOpen failed", ex);
            throw new WebSocketException(CloseCode.InternalServerError, "WebSocket endpoint failed", ex);
        }
    }

    @Override
    protected void onClose(CloseCode code, String reason, boolean remote) {
        try {
            listener.onClose(code != null ? code.getValue() : CloseCode.NormalClosure.getValue(), reason, remote);
        } catch (Exception ex) {
            LOG.warn("WebSocket listener onClose failed", ex);
        }
    }

    @Override
    protected void onMessage(WebSocketFrame frame) throws WebSocketException {
        try {
            OpCode opCode = frame.getOpCode();
            if (opCode == OpCode.Text) {
                continuationIsText = true;
                continuationBuffer.setLength(0);
                continuationBuffer.append(frame.getTextPayload());
                listener.onText(frame.getTextPayload());
            } else if (opCode == OpCode.Binary) {
                continuationIsText = false;
                continuationBuffer.setLength(0);
                listener.onBinary(frame.getBinaryPayload());
            } else if (opCode == OpCode.Continuation) {
                if (continuationIsText) {
                    String text = frame.getTextPayload();
                    continuationBuffer.append(text);
                    listener.onText(continuationBuffer.toString());
                } else {
                    byte[] data = frame.getBinaryPayload();
                    listener.onBinary(data);
                }
                continuationBuffer.setLength(0);
            }
        } catch (Exception ex) {
            LOG.warn("WebSocket listener onMessage failed for {}", frame.getOpCode(), ex);
            throw new WebSocketException(CloseCode.InternalServerError, "WebSocket message handler failed", ex);
        }
    }

    @Override
    protected void onPong(WebSocketFrame frame) {
    }

    @Override
    protected void onException(IOException exception) {
        try {
            listener.onError(exception);
        } catch (Exception listenerEx) {
            LOG.warn("WebSocket listener onError failed", listenerEx);
        }
        super.onException(exception);
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String pathVar(String name) {
        return pathVariables.get(name);
    }

    @Override
    public Map<String, String> pathVars() {
        return pathVariables;
    }

    @Override
    public String queryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    @Override
    public List<String> queryParams(String name) {
        return queryParams.getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return queryParams;
    }

    @Override
    public String header(String name) {
        List<String> values = headers.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    @Override
    public List<String> headers(String name) {
        return headers.getOrDefault(name, List.of());
    }

    @Override
    public RequestContext requestContext() {
        return requestContext;
    }

    @Override
    public boolean isOpen() {
        return super.isOpen();
    }

    @Override
    public void sendText(String text) throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket is already closed");
        }
        synchronized (sendLock) {
            super.send(Objects.requireNonNull(text, "text"));
        }
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket is already closed");
        }
        synchronized (sendLock) {
            super.send(Objects.requireNonNull(data, "data"));
        }
    }

    @Override
    public void ping(byte[] data) throws IOException {
        synchronized (sendLock) {
            super.ping(data != null ? data : new byte[0]);
        }
    }

    @Override
    public void close(int code, String reason) throws IOException {
        CloseCode closeCode = CloseCode.find(code);
        if (closeCode == null) {
            if (code < 3000 || code > 4999) {
                throw new IllegalArgumentException("Unsupported websocket close code: " + code
                    + ". Standard codes: 1000-1015, registered: 3000-3999, private: 4000-4999");
            }
            closeCode = CloseCode.GoingAway;
        }
        super.close(closeCode, reason, false);
    }

    private static Map<String, List<String>> snapshotHeaders(HttpExchange exchange) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            headers.put(name, List.copyOf(values));
        });
        return Map.copyOf(headers);
    }

}
