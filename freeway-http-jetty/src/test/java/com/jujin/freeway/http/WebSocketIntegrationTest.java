package com.jujin.freeway.http;

import com.jujin.freeway.boot.App;
import com.jujin.freeway.boot.Launcher;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketIntegrationTest {
    private App app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
        System.clearProperty("web.server.port");
        System.clearProperty("web.server.host");
        System.clearProperty("web.engine");
    }

    @Test
    void jettyEngineEchoesWebSocketMessages() throws Exception {
        int port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "jetty");

        app = Launcher.run(new TestAppModule());
        app.get(WebServer.class);

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<String> received = new CompletableFuture<>();
        CompletableFuture<String> closed = new CompletableFuture<>();
        WebSocket socket = client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws/lobby"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    received.complete(data.toString());
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closed.complete(reason);
                    return CompletableFuture.completedFuture(null);
                }
            })
            .join();

        socket.sendText("hello", true).join();
        String message = received.get(5, TimeUnit.SECONDS);
        assertTrue(message.startsWith("echo:hello:lobby:"));
        assertTrue(message.length() > "echo:hello:lobby:".length());

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        assertEquals("bye", closed.get(5, TimeUnit.SECONDS));
    }

    public static final class TestAppModule implements Module {
        @Override
        public void bind(Binder binder) {
            binder.contribute(WebSocketGroup.class).add(WebSocketGroup.of("/api",
                WebSocketRoute.of("/ws/{room}", session -> new WebSocketListener() {
                    @Override
                    public void onText(String text) throws Exception {
                        session.sendText("echo:" + text + ":" + session.pathVar("room") + ":" + session.requestContext().correlationId());
                    }
                })
            ));
            binder.contribute(Route.class).add(Route.get("/ping", ctx -> ctx.send(200, "pong")));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
