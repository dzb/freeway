package com.jujin.freeway.http.undertow;

import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketSession;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.xnio.IoUtils;

final class UndertowWebSocketSession implements WebSocketSession {
    private final WebSocketChannel channel;
    private final RequestContext requestContext;
    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> headers;
    private final Object sendLock = new Object();
    private volatile boolean localCloseRequested;
    private volatile WebSocketListener listener = WebSocketListener.NOOP;

    UndertowWebSocketSession(
        WebSocketChannel channel,
        RequestContext requestContext,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers
    ) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
        this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    void open(WebSocketListener listener) throws Exception {
        this.listener = listener != null ? listener : WebSocketListener.NOOP;
        this.listener.onOpen(this);
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) throws IOException {
                String text = message.getData();
                try {
                    UndertowWebSocketSession.this.listener.onText(text);
                } catch (Exception ex) {
                    fail(channel, ex);
                }
            }

            @Override
            protected void onFullBinaryMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                ByteBuffer merged = WebSockets.mergeBuffers(message.getData().getResource());
                byte[] data = new byte[merged.remaining()];
                merged.get(data);
                try {
                    UndertowWebSocketSession.this.listener.onBinary(data);
                } catch (Exception ex) {
                    fail(channel, ex);
                }
            }

            @Override
            protected void onFullCloseMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                CloseMessage closeMessage = new CloseMessage(WebSockets.mergeBuffers(message.getData().getResource()));
                try {
                    UndertowWebSocketSession.this.listener.onClose(
                        closeMessage.getCode(),
                        closeMessage.getReason(),
                        !localCloseRequested
                    );
                    if (!channel.isCloseFrameSent()) {
                        WebSockets.sendClose(closeMessage, channel, null);
                    }
                } catch (Exception ex) {
                    fail(channel, ex);
                }
            }

            @Override
            protected void onError(WebSocketChannel channel, Throwable error) {
                UndertowWebSocketSession.this.listener.onError(error);
            }
        });
        channel.resumeReceives();
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
        return channel.isOpen();
    }

    @Override
    public void sendText(String text) throws IOException {
        synchronized (sendLock) {
            WebSockets.sendTextBlocking(Objects.requireNonNull(text, "text"), channel);
        }
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        synchronized (sendLock) {
            WebSockets.sendBinaryBlocking(ByteBuffer.wrap(Objects.requireNonNull(data, "data")), channel);
        }
    }

    @Override
    public void ping(byte[] data) throws IOException {
        synchronized (sendLock) {
            WebSockets.sendPingBlocking(ByteBuffer.wrap(data != null ? data : new byte[0]), channel);
        }
    }

    @Override
    public void close(int code, String reason) throws IOException {
        localCloseRequested = true;
        WebSockets.sendCloseBlocking(code, reason != null ? reason : "", channel);
    }

    private void fail(WebSocketChannel channel, Throwable cause) throws IOException {
        try {
            listener.onError(cause);
        } catch (Exception ignored) {
        }
        try {
            close(1011, cause != null && cause.getMessage() != null ? cause.getMessage() : "websocket error");
        } catch (IOException ex) {
            IoUtils.safeClose(channel);
            throw ex;
        }
    }

}
