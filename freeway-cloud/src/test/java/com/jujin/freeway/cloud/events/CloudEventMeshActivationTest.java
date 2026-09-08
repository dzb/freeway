package com.jujin.freeway.cloud.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.EventBus;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Presence-driven activation of the event mesh (design principle: explicit
 * wins, presence activates, default stays off): configured peers alone start
 * the dialing side; an explicit {@code events.enabled=false} is the kill
 * switch; nothing set keeps the module inert; an invalid explicit value fails
 * startup naming the key.
 */
class CloudEventMeshActivationTest {

    private AppRuntime nodeA;
    private AppRuntime nodeB;

    @BeforeEach
    void randomPorts() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void cleanup() {
        if (nodeA != null) nodeA.close();
        if (nodeB != null) nodeB.close();
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.EVENTS_PEERS);
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
        System.clearProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS);
    }

    @Test
    void peersActivateTheMeshWithoutEnabled() throws Exception {
        // Listener side: explicit true (a pure listener has no peers to name).
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, "greet.");
        nodeB = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());

        // Dialing side: peers alone — no events.enabled anywhere.
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
        System.clearProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS);
        System.setProperty(CloudConfigKeys.EVENTS_PEERS,
            "127.0.0.1:" + nodeB.get(com.jujin.freeway.http.WebServer.class).port());
        nodeA = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());

        awaitMesh(nodeA, nodeB);

        var receivedByB = new CountDownLatch(1);
        var payloadAtB = new AtomicReference<String>();
        nodeB.get(EventBus.class).subscribe("greet.hello",
            payload -> { payloadAtB.set(String.valueOf(payload)); receivedByB.countDown(); });

        nodeA.get(EventBus.class).publish("greet.hello", "presence");
        assertTrue(receivedByB.await(awaitSeconds(), java.util.concurrent.TimeUnit.SECONDS),
            "peers alone must activate the mesh");
        assertEquals("presence", payloadAtB.get());
    }

    @Test
    void explicitFalseSuppressesConfiguredPeers() throws Exception {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, "greet.");
        nodeB = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());

        // Kill switch wins over presence: peers configured, enabled=false.
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "false");
        System.setProperty(CloudConfigKeys.EVENTS_PEERS,
            "127.0.0.1:" + nodeB.get(com.jujin.freeway.http.WebServer.class).port());
        nodeA = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());

        Thread.sleep(900);
        assertTrue(nodeA.get(PeerHub.class).connections().isEmpty(),
            "an explicit false must suppress the configured peer list");

        var receivedByB = new CountDownLatch(1);
        nodeB.get(EventBus.class).subscribe("greet.hello", payload -> receivedByB.countDown());
        nodeA.get(EventBus.class).publish("greet.hello", "bob");
        assertTrue(!receivedByB.await(700, java.util.concurrent.TimeUnit.MILLISECONDS),
            "a suppressed mesh must not deliver remotely");
    }

    @Test
    void unsetWithNoPeersStaysInert() throws Exception {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        nodeB = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());

        // Dialer with zero configuration: installing the module is not a
        // side effect — no mesh, no dials.
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
        nodeA = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());

        Thread.sleep(900);
        assertTrue(nodeA.get(PeerHub.class).connections().isEmpty(),
            "no explicit switch and no peers must leave the mesh unwired");
    }

    @Test
    void invalidEnabledValueFailsStartupNamingTheKey() {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "yolo");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule()));
        assertTrue(rootMessage(failure).contains("events.enabled"),
            "the failure must name the key: " + rootMessage(failure));
        assertTrue(rootMessage(failure).contains("yolo"),
            "the failure must show the offending value: " + rootMessage(failure));
    }

    // ==================== harness ====================

    private static void awaitMesh(AppRuntime a, AppRuntime b) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            boolean aSeesB = !a.get(PeerHub.class).connections().isEmpty();
            boolean bSeesA = !b.get(PeerHub.class).connections().isEmpty();
            if (aSeesB && bSeesA) return;
            Thread.sleep(50);
        }
        throw new AssertionError("mesh not established within 5s");
    }

    private static long awaitSeconds() {
        return 5;
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder seen = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            seen.append(t.getMessage()).append(" | ");
        }
        return seen.toString();
    }
}
