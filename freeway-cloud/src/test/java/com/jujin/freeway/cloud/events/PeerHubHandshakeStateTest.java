package com.jujin.freeway.cloud.events;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.websocket.WebSocketEndpoint;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.http.websocket.WebSocketSession;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mesh handshake state machine (P1-1): hello must be the first frame of a
 * session and is one-shot. The token check lives in the hello path, so a CE
 * frame before hello must be closed (server leg) / aborted (client leg)
 * rather than dispatched — otherwise any client that can open a socket skips
 * admission and injects TOPIC events (the allowlist is accept-any by
 * default). Uses real WS transport on both legs.
 */
class PeerHubHandshakeStateTest {

    /** A valid TOPIC-channel CE frame — exactly what a pre-hello attacker sends. */
    private static final String CE_TOPIC_FRAME =
        "{\"specversion\":\"1.0\",\"id\":\"attack-1\",\"source\":\"freeway://attacker\","
            + "\"type\":\"greet.hello\",\"fwchannel\":\"topic\",\"fworigin\":\"attacker-1\","
            + "\"data\":\"bob\"}";

    private static final String HELLO =
        "{\"proto\":1,\"origin\":\"probe-1\",\"subscribe\":[]}";

    private AppRuntime node;

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void cleanup() {
        if (node != null) {
            node.close();
        }
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
        System.clearProperty(CloudConfigKeys.EVENTS_PEERS);
        System.clearProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS);
        System.clearProperty(CloudConfigKeys.EVENTS_TOKEN);
    }

    /** A real events node (HttpModule + CloudEventModule). */
    private AppRuntime startEventsNode(String subscriptions, String token) {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, subscriptions);
        if (token != null) {
            System.setProperty(CloudConfigKeys.EVENTS_TOKEN, token);
        }
        return FreewayApp.run(new HttpModule(), new CloudEventModule());
    }

    private static int port(AppRuntime app) {
        return app.get(WebServer.class).port();
    }

    // ── server leg ────────────────────────────────────────────────────────

    @Test
    void ceFrameBeforeHelloIsClosedWithoutReachingAdmission() throws Exception {
        // Token configured: a hello-less attacker would be rejected by the
        // state machine (1002 "hello expected") before any token logic runs.
        node = startEventsNode("greet.", "s3cret");
        List<CloudEventEnvelope.Parsed> intercepted = new ArrayList<>();
        node.get(PeerHub.class).addInterceptor(frame -> {
            synchronized (intercepted) {
                intercepted.add(frame);
            }
            return true;
        });
        CountDownLatch delivered = new CountDownLatch(1);
        node.get(EventBus.class).subscribe("greet.hello", p -> delivered.countDown());

        RawClient attacker = RawClient.connect(port(node));
        attacker.send(CE_TOPIC_FRAME);

        assertEquals(1002, attacker.awaitCloseCode(5),
            "pre-hello CE frame must be closed with 1002, not dispatched");
        assertTrue(intercepted.isEmpty(), "pre-hello CE frame must never reach receive()");
        assertEquals(1, delivered.getCount(), "no event may reach the local bus");
        assertTrue(node.get(PeerHub.class).connections().isEmpty(),
            "a session that never completed hello must not be registered");
    }

    @Test
    void secondHelloOnAnEstablishedSessionIsClosed() throws Exception {
        node = startEventsNode("greet.", null);
        RawClient peer = RawClient.connect(port(node));

        peer.send(HELLO);
        peer.send(HELLO); // hello is one-shot per session

        assertEquals(1002, peer.awaitCloseCode(5),
            "a second hello must be closed with 1002");
        assertTrue(await(3000, () -> node.get(PeerHub.class).connections().isEmpty()),
            "the connection registered by the first hello must be cleaned up on close");
    }

    // ── client leg ────────────────────────────────────────────────────────

    @Test
    void ceFrameBeforeAckIsAbortedAndRedialed() throws Exception {
        // A peer that sends CE frames but never answers hello must not get
        // its frames dispatched; the client leg must abort and re-dial.
        MisbehavingServer fake = new MisbehavingServer();
        AppRuntime fakeNode = FreewayApp.run(new HttpModule(), new MisbehavingServerModule(fake));
        try {
            System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
            System.setProperty(CloudConfigKeys.EVENTS_PEERS,
                "127.0.0.1:" + fakeNode.get(WebServer.class).port());
            node = FreewayApp.run(new HttpModule(), new CloudEventModule());
            PeerHub hub = node.get(PeerHub.class);
            List<CloudEventEnvelope.Parsed> intercepted = new ArrayList<>();
            hub.addInterceptor(frame -> {
                synchronized (intercepted) {
                    intercepted.add(frame);
                }
                return true;
            });

            assertTrue(await(5000, () -> fake.opens.get() >= 2),
                "pre-ack CE frame must abort the session and trigger a re-dial"
                    + " (server-side opens=" + fake.opens.get() + ")");
            assertTrue(intercepted.isEmpty(),
                "pre-ack CE frames must never reach receive() on the client leg");
            assertTrue(hub.connections().isEmpty(),
                "a session without an ack must never register a connection");
        } finally {
            fakeNode.close();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static boolean await(long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    /** Minimal raw WS client — no subprotocol, no mesh behavior. */
    private static final class RawClient {
        private final WebSocket socket;
        private final CompletableFuture<Integer> closeCode;

        private RawClient(WebSocket socket, CompletableFuture<Integer> closeCode) {
            this.socket = socket;
            this.closeCode = closeCode;
        }

        static RawClient connect(int port) throws Exception {
            CompletableFuture<Integer> closeCode = new CompletableFuture<>();
            CompletableFuture<Void> opened = new CompletableFuture<>();
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/cloud/events"),
                    new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                            opened.complete(null);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket,
                                int statusCode, String reason) {
                            closeCode.complete(statusCode);
                            return null;
                        }
                    })
                .get(5, TimeUnit.SECONDS);
            opened.get(5, TimeUnit.SECONDS);
            return new RawClient(socket, closeCode);
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        int awaitCloseCode(int seconds) throws Exception {
            return closeCode.get(seconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Fake events server: accepts the upgrade and immediately sends a CE
     * frame — the pre-ack attack — then never answers hello.
     */
    private static final class MisbehavingServer {
        final AtomicInteger opens = new AtomicInteger();

        final WebSocketEndpoint endpoint = session -> {
            opens.incrementAndGet();
            return new WebSocketListener() {
                @Override
                public void onOpen(WebSocketSession opened) throws Exception {
                    opened.sendText(CE_TOPIC_FRAME);
                }
            };
        };
    }

    private record MisbehavingServerModule(MisbehavingServer server) implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(WebSocketRoute.class)
                .add("fake-events", WebSocketRoute.of(
                    CloudConfigKeys.EVENTS_PATH_DEFAULT, server.endpoint));
        }
    }
}
