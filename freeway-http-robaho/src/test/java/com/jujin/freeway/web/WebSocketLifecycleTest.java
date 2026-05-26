package com.jujin.freeway.web;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketLifecycleTest {
    private Container container;

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.close();
        }
        System.clearProperty("web.server.port");
        System.clearProperty("web.server.host");
    }

    @Test
    void invokesOpenAndErrorCallbacks() throws Exception {
        int port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));

        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean errored = new AtomicBoolean();

        container = Freeway.create(
            new WebModule(),
            new RobahoWebEngineModule(),
            binder -> binder.contribute(WebSocketGroup.class).add(WebSocketGroup.of("/ws",
                WebSocketRoute.of("/lifecycle", session -> new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocketSession session) throws Exception {
                        opened.set(true);
                    }

                    @Override
                    public void onText(String text) throws Exception {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public void onError(Throwable error) {
                        errored.set(true);
                    }
                })
            ))
        );
        container.get(WebServer.class);

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<Integer> closed = new CompletableFuture<>();
        WebSocket socket = client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/lifecycle"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closed.complete(statusCode);
                    return CompletableFuture.completedFuture(null);
                }
            })
            .join();

        assertTrue(opened.get());
        socket.sendText("boom", true).join();
        assertTrue(closed.get(5, TimeUnit.SECONDS) != WebSocket.NORMAL_CLOSURE);
        assertTrue(errored.get());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
