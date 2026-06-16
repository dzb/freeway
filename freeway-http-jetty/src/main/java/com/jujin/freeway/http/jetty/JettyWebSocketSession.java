package com.jujin.freeway.http.jetty;

import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.websocket.WebSocketSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

final class JettyWebSocketSession implements WebSocketSession {
    private final Session session;
    private final RequestContext requestContext;
    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> headers;
    private final Object sendLock = new Object();
    private volatile boolean localCloseRequested;

    JettyWebSocketSession(
        Session session,
        RequestContext requestContext,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
        this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
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
        return session.isOpen();
    }

    @Override
    public void sendText(String text) throws IOException {
        synchronized (sendLock) {
            Callback.Completable callback = new Callback.Completable();
            session.sendText(Objects.requireNonNull(text, "text"), callback);
            callback.join();
        }
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        synchronized (sendLock) {
            Callback.Completable callback = new Callback.Completable();
            session.sendBinary(ByteBuffer.wrap(Objects.requireNonNull(data, "data")), callback);
            callback.join();
        }
    }

    @Override
    public void ping(byte[] data) throws IOException {
        synchronized (sendLock) {
            Callback.Completable callback = new Callback.Completable();
            session.sendPing(ByteBuffer.wrap(data != null ? data : new byte[0]), callback);
            callback.join();
        }
    }

    @Override
    public void close(int code, String reason) throws IOException {
        localCloseRequested = true;
        Callback.Completable callback = new Callback.Completable();
        session.close(code, reason != null ? reason : "", callback);
        callback.join();
    }

    boolean localCloseRequested() {
        return localCloseRequested;
    }
}
