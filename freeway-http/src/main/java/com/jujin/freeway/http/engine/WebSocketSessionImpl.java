package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.websocket.WebSocketSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WebSocketSessionImpl implements WebSocketSession {

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, List<String>> headers;
    private final InputStream in;
    private final OutputStream out;
    private final Map<String, String> pathVariables;
    private final Http11Connection connection;

    private final RequestContext requestContext = RequestContext.create();
    private volatile boolean open = true;

    // lazy
    private Map<String, List<String>> queryParams;

    WebSocketSessionImpl(String method, String path, String rawQuery,
                         Map<String, List<String>> headers,
                         InputStream in, OutputStream out,
                         Map<String, String> pathVariables,
                         Http11Connection connection) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.headers = headers;
        this.in = in;
        this.out = out;
        this.pathVariables = pathVariables != null ? Map.copyOf(pathVariables) : Map.of();
        this.connection = connection;
    }

    @Override public String method() { return method; }
    @Override public String path() { return path; }
    @Override public String pathVar(String name) { return pathVariables.get(name); }
    @Override public Map<String, String> pathVars() { return pathVariables; }
    @Override public RequestContext requestContext() { return requestContext; }
    @Override public boolean isOpen() { return open; }

    @Override
    public String queryParam(String name) {
        return ensureQueryParams().getOrDefault(name, List.of()).stream()
            .findFirst().orElse(null);
    }

    @Override
    public List<String> queryParams(String name) {
        return ensureQueryParams().getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return Collections.unmodifiableMap(ensureQueryParams());
    }

    @Override
    public String header(String name) {
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().getFirst();
            }
        }
        return null;
    }

    @Override
    public List<String> headers(String name) {
        List<String> v = headers.get(name);
        if (v != null) return List.copyOf(v);
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return List.copyOf(e.getValue());
            }
        }
        return List.of();
    }

    @Override
    public void sendText(String text) throws IOException {
        WebSocket.writeFrame(out, new WebSocketFrame(OpCode.Text, true, text));
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        WebSocket.writeFrame(out, new WebSocketFrame(OpCode.Binary, true, data));
    }

    @Override
    public void ping(byte[] data) throws IOException {
        WebSocket.writeFrame(out, new WebSocketFrame(OpCode.Ping, true, data));
    }

    @Override
    public void close(int code, String reason) throws IOException {
        if (!open) return;
        open = false;
        var closePayload = buildClosePayload(code, reason);
        WebSocket.writeFrame(out,
            new WebSocketFrame(OpCode.Close, true, closePayload));
        try { connection.close(); } catch (Exception ignored) {}
    }

    // --- package-private ---

    void markOpen() { open = true; }

    void markClosed(CloseCode code, String reason) { open = false; }
    void markClosed(int code, String reason) { open = false; }

    // --- internal ---

    private Map<String, List<String>> ensureQueryParams() {
        if (queryParams == null) {
            var map = new LinkedHashMap<String, List<String>>();
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    String k = eq >= 0 ? decode(pair.substring(0, eq)) : decode(pair);
                    String v = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
                    map.computeIfAbsent(k, ignored -> new java.util.ArrayList<>()).add(v);
                }
            }
            queryParams = map;
        }
        return queryParams;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static byte[] buildClosePayload(int code, String reason) {
        byte[] reasonBytes = reason.getBytes(WebSocketFrame.TEXT_CHARSET);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >> 8 & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return payload;
    }
}
