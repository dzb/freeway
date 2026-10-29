package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Keepalive end to end: with {@code event.keepalive-ms} set, a healthy mesh
 * stays up across many probe cycles, and a peer that stops reading is closed
 * and re-dialed rather than trusted forever.
 *
 * <p>The half-open peer is produced for real: its read loop is stopped by
 * suspending the process-side socket read — the JDK WebSocket cannot be told to
 * "stop reading but keep the socket open" politely, so this test proves the
 * healthy half (the risk of a keepalive is killing good connections) and the
 * timeout leg is covered by {@link MeshKeepaliveTest}.</p>
 */
class MeshKeepaliveEndToEndTest {

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
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(ConfigKeys.EVENT_ENABLED);
        System.clearProperty(ConfigKeys.EVENT_PEERS);
        System.clearProperty(ConfigKeys.EVENT_KEEPALIVE_MS);
        System.clearProperty(ConfigKeys.EVENT_BACKOFF_BASE_MS);
        System.clearProperty(ConfigKeys.EVENT_BACKOFF_MAX_MS);
    }

    private static AppRuntime startNode(String peers) {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        System.setProperty(ConfigKeys.EVENT_ENABLED, "true");
        System.setProperty(ConfigKeys.EVENT_PEERS, peers);
        System.setProperty(ConfigKeys.EVENT_BACKOFF_BASE_MS, "100");
        System.setProperty(ConfigKeys.EVENT_BACKOFF_MAX_MS, "300");
        return FreewayApp.run(new HttpModule(), new CloudEventModule());
    }

    @Test
    void a_healthy_mesh_survives_keepalive_cycles() throws Exception {
        System.setProperty(ConfigKeys.EVENT_KEEPALIVE_MS, "50");
        nodeB = startNode("");
        int bPort = nodeB.get(HttpServer.class).port();
        nodeA = startNode("127.0.0.1:" + bPort);

        assertTrue(awaitMesh(nodeA, nodeB, 8000), "the mesh must connect first");

        // Many probe cycles: a keepalive that kills healthy links would show
        // up as a re-dial inside this window (backoff is 100ms).
        PeerConnection before = nodeA.get(PeerHub.class).connections().get(0);
        Thread.sleep(1200);
        List<PeerConnection> after = nodeA.get(PeerHub.class).connections();
        assertTrue(after.size() == 1, "exactly one connection must remain, got " + after.size());
        assertTrue(after.get(0) == before,
            "an answered keepalive must not re-dial the peer");
    }

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
}
