package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.event.EventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Design-doc E2 contract tests: two real nodes (each a full FreewayApp with
 * HttpModule + CloudEventModule) exchanging CloudEvents 1.0 frames over a
 * real WebSocket mesh — round-trip both directions, sender-side prefix
 * filtering, plane separation (a local bus publish never crosses the
 * boundary), the Defer commit gate on the cloud plane, and the wire subject
 * as an explicit publish argument.
 */
class CloudEventBusTest {

    record GreetEvent(String name) {}

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
        System.clearProperty(CloudConfigKeys.EVENT_PEERS);
        System.clearProperty(CloudConfigKeys.EVENT_ENABLED);
    }

    /** Declares mesh interest for one prefix; payloads land in {@code sink}. */
    private static ModuleEx listening(String prefix, Consumer<String> sink) {
        return binder -> binder.contribute(CloudEventSubscription.class)
            .add("listen-" + prefix, CloudEventSubscription.of(prefix, String.class, sink::accept));
    }

    /** Starts a node dialing nobody (pure listener side unless given peers). */
    private AppRuntime start(String peers, ModuleEx... extra) {
        System.setProperty(CloudConfigKeys.EVENT_ENABLED, "true");
        if (peers != null) {
            System.setProperty(CloudConfigKeys.EVENT_PEERS, peers);
        }
        List<ModuleEx> mods = new ArrayList<>();
        mods.add(new HttpModule());
        mods.add(new CloudEventModule());
        for (ModuleEx m : extra) mods.add(m);
        return FreewayApp.run(new String[0], mods.toArray(new ModuleEx[0]));
    }

    private static int port(AppRuntime app) {
        return app.get(com.jujin.freeway.http.HttpServer.class).port();
    }

    /** Waits until both nodes see the mesh connection established. */
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

    private static void awaitUntil(java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within 5s");
    }

    @Test
    void topicEventRoundTripBothDirectionsOverOneConnection() throws Exception {
        var atB = new CopyOnWriteArrayList<String>();
        var atA = new CopyOnWriteArrayList<String>();
        nodeB = start(null, listening("greet.", atB::add));
        nodeA = start("127.0.0.1:" + port(nodeB), listening("ack.", atA::add));
        awaitMesh(nodeA, nodeB);

        nodeA.get(CloudEventBus.class).publish("greet.hello", "bob");
        awaitUntil(() -> !atB.isEmpty());
        assertEquals(List.of("bob"), atB);

        // Reverse direction over the SAME connection (A dialed B).
        nodeB.get(CloudEventBus.class).publish("ack.done", "ok");
        awaitUntil(() -> !atA.isEmpty());
        assertEquals(List.of("ok"), atA);
    }

    @Test
    void subscriptionPrefixFiltersAtTheSender() throws Exception {
        var atB = new CopyOnWriteArrayList<String>();
        nodeB = start(null, listening("greet.", atB::add));
        nodeA = start("127.0.0.1:" + port(nodeB));
        awaitMesh(nodeA, nodeB);

        // B subscribed only to "greet." — this must not even be translated.
        nodeA.get(CloudEventBus.class).publish("user.created", "spam");
        Thread.sleep(700);
        assertTrue(atB.isEmpty(), "unsubscribed topic must be filtered at the sender");
        assertEquals(0, nodeA.get(CloudEventBus.class).stats().framesSent(),
            "filter-then-translate: no interested peer means no serialization, no send");
    }

    @Test
    void localBusPublishNeverCrossesTheBoundary() throws Exception {
        var atB = new CopyOnWriteArrayList<String>();
        nodeB = start(null, listening("greet.", atB::add));
        nodeA = start("127.0.0.1:" + port(nodeB));
        awaitMesh(nodeA, nodeB);

        // The plane separation, pinned: loading the mesh module does NOT
        // turn a local bus publish into a broadcast.
        nodeA.get(EventBus.class).publish("greet.hello", "local-only");
        Thread.sleep(700);
        assertTrue(atB.isEmpty(), "a local fact must stay a local fact");
        assertEquals(0, nodeA.get(CloudEventBus.class).stats().published(),
            "the cloud plane was never handed this publish");
    }

    @Test
    void meshPublishIsBufferedByDeferAndDiscardedOnRollback() {
        // Unit level: an unwired hub measures only the commit gate — the
        // flush itself (never the transport) is what Defer controls.
        PeerHub hub = new PeerHub();
        CloudEventBus mesh = new CloudEventBus(hub, new JsonCodecDefault(), List.of());

        Defer.within(() -> {
            mesh.publish("greet.hello", "pending");
            assertEquals(0, mesh.stats().published(),
                "inside a Defer scope the publish is buffered, not sent");
        });
        assertEquals(1, mesh.stats().published(), "commit drains the buffered publish");

        Defer.within(scope -> {
            mesh.publish("greet.hello", "doomed");
            scope.rollback();
        });
        assertEquals(1, mesh.stats().published(),
            "a rolled-back scope must discard: an uncommitted fact never leaves the JVM");

        assertThrows(IllegalStateException.class, () -> Defer.within(() -> {
            mesh.publish("greet.hello", "doomed-too");
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, mesh.stats().published(),
            "an aborted scope discards the same way");
    }

    @Test
    void publishedFramesCarryTheirExplicitSubjectAndTopicType() {
        var codec = new JsonCodecDefault();
        var json = CloudEventEnvelope.translate(
            "order.created", new GreetEvent("x"), "order-42",
            "node-a@127.0.0.1:8080", "orders", codec, "wire-id-1");
        var frame = com.jujin.freeway.commons.json.JsonUtils.parseObject(json);

        assertEquals("order-42", frame.getString("subject"),
            "the subject is the publish-site argument, carried verbatim");
        assertEquals("order.created", frame.getString("type"),
            "the topic is the type — class names never reach the wire");
        assertEquals("topic", frame.getString(CloudEventEnvelope.EXT_CHANNEL));
        assertEquals("wire-id-1", frame.getString("id"));

        var bare = com.jujin.freeway.commons.json.JsonUtils.parseObject(
            CloudEventEnvelope.translate("order.created", "plain", null,
                "node-a@127.0.0.1:8080", "orders", codec, "wire-id-2"));
        assertFalse(bare.containsKey("subject"),
            "no subject argument means no subject attribute: " + bare);
    }

    @Test
    void disabledModuleIsInert() {
        System.setProperty(CloudConfigKeys.EVENT_ENABLED, "false");
        nodeA = FreewayApp.run(new String[0], new HttpModule(), new CloudEventModule());
        var mesh = nodeA.get(CloudEventBus.class);
        // Publish against an unwired mesh: a counted, debug-logged no-op.
        mesh.publish("greet.hello", "bob");
        assertEquals(0, nodeA.get(CloudEventBus.class).stats().framesSent());
        assertFalse(nodeA.get(PeerHub.class).wired(),
            "disabled CloudEventModule must not wire the mesh hub");
    }
}
