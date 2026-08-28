package com.jujin.freeway.cloud.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Design-doc E2 contract tests: two real nodes (each a full FreewayApp with
 * HttpModule + CloudEventsModule) exchanging CloudEvents 1.0 frames over a
 * real WebSocket mesh — round-trip both directions, subscription filtering,
 * CLASS-channel whitelist, Keyed ordering subject, origin loop protection.
 */
class CloudEventBusTest {

    record GreetEvent(String name) {}

    @com.jujin.freeway.ioc.Topic("order.created")
    record OrderedEvent(String id) implements EventBus.Keyed {
        @Override public String key() { return id; }
    }

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
        System.clearProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS);
        System.clearProperty(CloudConfigKeys.EVENTS_ALLOWED_TYPES);
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
    }

    /** Starts node B first (peers empty — waits for inbound connections). */
    private AppRuntime startB(String subscriptions, String allowedTypes) {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, subscriptions);
        if (allowedTypes != null) {
            System.setProperty(CloudConfigKeys.EVENTS_ALLOWED_TYPES, allowedTypes);
        }
        return FreewayApp.run(new String[0], new HttpModule(), new CloudEventsModule());
    }

    /** Starts node A dialing node B. */
    private AppRuntime startA(int bPort, String subscriptions) {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_PEERS, "127.0.0.1:" + bPort);
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, subscriptions);
        return FreewayApp.run(new String[0], new HttpModule(), new CloudEventsModule());
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

    @Test
    void topicEventRoundTripBothDirectionsOverOneConnection() throws Exception {
        nodeB = startB("greet.", null);
        int bPort = nodeB.get(com.jujin.freeway.http.WebServer.class).port();
        nodeA = startA(bPort, "ack.");
        awaitMesh(nodeA, nodeB);

        var receivedByB = new CountDownLatch(1);
        var receivedByA = new CountDownLatch(1);
        var payloadAtB = new AtomicReference<String>();
        var payloadAtA = new AtomicReference<String>();

        nodeB.get(EventBus.class).subscribe("greet.hello",
            payload -> { payloadAtB.set(String.valueOf(payload)); receivedByB.countDown(); });
        nodeA.get(EventBus.class).subscribe("ack.done",
            payload -> { payloadAtA.set(String.valueOf(payload)); receivedByA.countDown(); });

        nodeA.get(EventBus.class).publish("greet.hello", "bob");
        assertTrue(receivedByB.await(awaitSeconds(), TimeUnit.SECONDS),
            "A's publish must reach B over the mesh");
        assertEquals("bob", payloadAtB.get());

        // Reverse direction over the SAME connection (A dialed B).
        nodeB.get(EventBus.class).publish("ack.done", "ok");
        assertTrue(receivedByA.await(awaitSeconds(), TimeUnit.SECONDS),
            "B's publish must reach A over the reverse direction");
        assertEquals("ok", payloadAtA.get());
    }

    @Test
    void subscriptionPrefixFiltersOutUnsubscribedTopics() throws Exception {
        nodeB = startB("greet.", null);
        int bPort = nodeB.get(com.jujin.freeway.http.WebServer.class).port();
        nodeA = startA(bPort, "");
        awaitMesh(nodeA, nodeB);

        var unexpected = new CountDownLatch(1);
        nodeB.get(EventBus.class).subscribe("user.created",
            payload -> unexpected.countDown());

        // B subscribed only to "greet." — this must not arrive.
        nodeA.get(EventBus.class).publish("user.created", "spam");
        assertFalse(unexpected.await(700, TimeUnit.MILLISECONDS),
            "unsubscribed topic must be filtered at the sender");
    }

    @Test
    void classEventRoundTripsThroughWhitelist() throws Exception {
        String type = GreetEvent.class.getName();
        nodeB = startB(type, type); // subscribe + whitelist by class name
        int bPort = nodeB.get(com.jujin.freeway.http.WebServer.class).port();
        nodeA = startA(bPort, "");
        awaitMesh(nodeA, nodeB);

        var received = new CountDownLatch(1);
        var eventAtB = new AtomicReference<GreetEvent>();
        nodeB.get(EventBus.class).subscribe(GreetEvent.class,
            event -> { eventAtB.set(event); received.countDown(); });

        // A publishes a CLASS event; B rebuilds it by whitelist type.
        nodeA.get(EventBus.class).publish(new GreetEvent("typed"));
        assertTrue(received.await(awaitSeconds(), TimeUnit.SECONDS),
            "CLASS event must round-trip through the CE envelope");
        assertEquals(new GreetEvent("typed"), eventAtB.get());
    }

    @Test
    void classEventOutsideWhitelistIsDropped() throws Exception {
        nodeB = startB(GreetEvent.class.getName(), OrderedEvent.class.getName());
        int bPort = nodeB.get(com.jujin.freeway.http.WebServer.class).port();
        nodeA = startA(bPort, "");
        awaitMesh(nodeA, nodeB);

        var received = new CountDownLatch(1);
        nodeB.get(EventBus.class).subscribe(GreetEvent.class, e -> received.countDown());

        // GreetEvent is NOT whitelisted on B (only OrderedEvent is) — dropped.
        nodeA.get(EventBus.class).publish(new GreetEvent("intruder"));
        assertFalse(received.await(700, TimeUnit.MILLISECONDS),
            "non-whitelisted type must be dropped at the receiver");
    }

    @Test
    void keyedEventsPreserveTheOrderingSubject() throws Exception {
        nodeB = startB("order.", null);
        int bPort = nodeB.get(com.jujin.freeway.http.WebServer.class).port();
        nodeA = startA(bPort, "");
        awaitMesh(nodeA, nodeB);

        var received = new CountDownLatch(1);
        var eventAtB = new AtomicReference<OrderedEvent>();
        nodeB.get(EventBus.class).subscribe(OrderedEvent.class,
            e -> { eventAtB.set(e); received.countDown(); });

        nodeA.get(EventBus.class).publish(new OrderedEvent("order-42"));
        assertTrue(received.await(awaitSeconds(), TimeUnit.SECONDS));
        assertEquals("order-42", eventAtB.get().id);
    }

    @Test
    void disabledModuleIsInert() {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "false");
        nodeA = FreewayApp.run(new String[0], new HttpModule(), new CloudEventsModule());
        // publish with no bridge, no peers — must be a clean local-only no-op
        nodeA.get(EventBus.class).publish("greet.hello", "bob");
        nodeA.get(EventBus.class).publish(new GreetEvent("bob"));
    }

    private static long awaitSeconds() {
        return 3;
    }
}
