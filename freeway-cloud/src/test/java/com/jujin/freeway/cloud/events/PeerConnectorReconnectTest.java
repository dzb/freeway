package com.jujin.freeway.cloud.events;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;

import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbound reconnect semantics (P1-2): whether a lost outbound session is
 * re-dialed is decided by the hub registry (is the origin still served by a
 * live connection?), never by <em>who</em> closed the transport. A
 * send-failure drop therefore re-dials — the connector must not confuse it
 * with duplicate resolution, where the surviving twin keeps the origin
 * served and no re-dial may happen.
 */
class PeerConnectorReconnectTest {

    private AppRuntime nodeA;
    private AppRuntime nodeB;

    @AfterEach
    void cleanup() {
        if (nodeA != null) {
            nodeA.close();
        }
        if (nodeB != null) {
            nodeB.close();
        }
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
        System.clearProperty(CloudConfigKeys.EVENTS_PEERS);
        System.clearProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS);
    }

    private static AppRuntime startEventsNode(String peers, String subscriptions) {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_PEERS, peers);
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, subscriptions);
        return FreewayApp.run(new HttpModule(), new CloudEventModule());
    }

    private static int port(AppRuntime app) {
        return app.get(WebServer.class).port();
    }

    /**
     * The sink's failed-send drop does {@code hub.unregister(peer)} then
     * {@code peer.close()} (CloudEventSink) — that exact sequence on the
     * outbound leg must end with the connector dialing again, because after
     * the drop nothing serves that origin anymore. (A real outbound send
     * failure surfaces through the sender's completion stage instead of the
     * sink's synchronous false branch, so the drop sequence is driven
     * directly here — same code path the sink executes.)
     */
    @Test
    void failedSendDropOnOutboundConnectionIsRedialed() throws Exception {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
        nodeB = startEventsNode("", "greet.");
        int bPort = port(nodeB);
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
        nodeA = startEventsNode("127.0.0.1:" + bPort, "");
        assertTrue(awaitMesh(nodeA, nodeB, 8000), "mesh must establish before the drop");

        PeerHub hubA = nodeA.get(PeerHub.class);
        PeerConnection outbound = hubA.connections().get(0);
        assertTrue(outbound.isOutbound(), "A dialed B, so A's connection must be outbound");

        // CloudEventSink's failed-send branch (unregister + close).
        hubA.unregister(outbound);
        outbound.close();

        assertTrue(awaitMesh(nodeA, nodeB, 8000),
            "the dropped outbound peer must be re-dialed, not lost until restart");
    }

    /**
     * Duplicate resolution (both nodes dial each other) closes the losing
     * session while the surviving twin is registered — that close must NOT
     * trigger a re-dial, or the mesh would reconnect-loop forever. The mesh
     * must settle on one live connection per side and keep it.
     */
    @Test
    void duplicateResolutionCloseDoesNotReconnect() throws Exception {
        int aPort = freePort();
        int bPort = freePort();
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(aPort));
        nodeA = startEventsNode("127.0.0.1:" + bPort, "");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(bPort));
        nodeB = startEventsNode("127.0.0.1:" + aPort, "");

        assertTrue(awaitStable(nodeA, nodeB, 1500, 12_000),
            "the mesh must converge on one stable connection per side — a duplicate-"
                + "resolution close must not re-dial (A="
                + nodeA.get(PeerHub.class).connections().size()
                + ", B=" + nodeB.get(PeerHub.class).connections().size() + ")");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Waits until both hubs see a registered connection again. */
    private static boolean awaitMesh(AppRuntime a, AppRuntime b, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!a.get(PeerHub.class).connections().isEmpty()
                    && !b.get(PeerHub.class).connections().isEmpty()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * Waits until both hubs hold exactly one connection and the same
     * connection objects persist for {@code stableMs} — any re-dial after
     * the settle point would replace the registered connection and restart
     * the window.
     */
    private static boolean awaitStable(AppRuntime a, AppRuntime b, long stableMs,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long stableSince = -1;
        PeerConnection seenA = null;
        PeerConnection seenB = null;
        while (System.currentTimeMillis() < deadline) {
            List<PeerConnection> ca = a.get(PeerHub.class).connections();
            List<PeerConnection> cb = b.get(PeerHub.class).connections();
            long now = System.currentTimeMillis();
            if (ca.size() == 1 && cb.size() == 1) {
                if (stableSince < 0 || ca.get(0) != seenA || cb.get(0) != seenB) {
                    stableSince = now;
                    seenA = ca.get(0);
                    seenB = cb.get(0);
                } else if (now - stableSince >= stableMs) {
                    return true;
                }
            } else {
                stableSince = -1;
                seenA = null;
                seenB = null;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
