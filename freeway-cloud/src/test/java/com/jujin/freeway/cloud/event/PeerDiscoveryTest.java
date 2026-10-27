package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.ioc.ModuleEx;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The discovery-fed half of the peer roster: a registry adapter replaces it
 * wholesale, and an address left out of the snapshot is a node that left.
 *
 * <p>Two facts are under test and the second is what the old add-only seam
 * could not do: a discovered peer must be dropped — dial loop interrupted,
 * session closed, <em>and not re-dialed</em> — and a peer that stays in the
 * snapshot must not be disturbed, because re-dialing it would reset its
 * backoff and turn every watch push into a reconnect.</p>
 *
 * <p>Backoff is shortened through config so "no re-dial happened" can be
 * asserted in wall-clock time: a re-dial would fire immediately (the backoff
 * is zero after a successful handshake), so surviving several max-backoff
 * intervals is the evidence.</p>
 */
class PeerDiscoveryTest {

    private static final String FAST_BACKOFF_BASE_MS = "100";
    private static final String FAST_BACKOFF_MAX_MS = "300";

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
        System.clearProperty(ConfigKeys.EVENT_BACKOFF_BASE_MS);
        System.clearProperty(ConfigKeys.EVENT_BACKOFF_MAX_MS);
        System.clearProperty(ConfigKeys.EVENT_DISCOVERY_FEED_MS);
    }

    /**
     * A discovered address is dialed on arrival and gone on the next snapshot.
     * This is also the end-to-end proof that the seam exists: the address is
     * handed to the bus, and a node appears on the other side of it.
     */
    @Test
    void aDiscoveredPeerIsDialedAndRemovedByTheNextSnapshot() throws Exception {
        nodeB = startNode("");
        int bPort = nodeB.get(HttpServer.class).port();
        nodeA = startNode("");
        awaitWired(nodeA);

        String peer = "127.0.0.1:" + bPort;
        nodeA.get(CloudEventBus.class).replaceDiscoveredPeers(List.of(peer));
        assertTrue(awaitConnections(nodeA, 1, 5000),
            "a discovered peer must be dialed, not just recorded: " + peer);

        nodeA.get(CloudEventBus.class).replaceDiscoveredPeers(List.of());
        assertTrue(awaitConnections(nodeA, 0, 3000),
            "an address missing from the snapshot must be disconnected");

        // The removal's own close must not re-dial: the roster entry is gone,
        // so `handleDisconnect` finds nothing to reconnect. Three max-backoff
        // intervals is ~1s — a re-dial would have fired in the first millisecond.
        Thread.sleep(1000);
        assertTrue(nodeA.get(PeerHub.class).connections().isEmpty(),
            "a removed peer must stay removed: "
                + nodeA.get(PeerHub.class).connections().size() + " connection(s) came back");
    }

    /** A snapshot that still names the peer must leave its connection alone. */
    @Test
    void aKeptPeerIsNotRedialedByTheNextSnapshot() throws Exception {
        nodeB = startNode("");
        int bPort = nodeB.get(HttpServer.class).port();
        nodeA = startNode("");
        awaitWired(nodeA);

        String peer = "127.0.0.1:" + bPort;
        nodeA.get(CloudEventBus.class).replaceDiscoveredPeers(List.of(peer));
        assertTrue(awaitConnections(nodeA, 1, 5000), "mesh must establish first");

        PeerConnection before = nodeA.get(PeerHub.class).connections().get(0);
        nodeA.get(CloudEventBus.class).replaceDiscoveredPeers(List.of(peer));
        Thread.sleep(500);

        List<PeerConnection> after = nodeA.get(PeerHub.class).connections();
        assertEquals(1, after.size(), "re-sending the same address must not add a connection");
        assertSame(before, after.get(0),
            "a peer still present in the snapshot must keep its connection — re-dialing it "
                + "would reset its backoff on every watch push");
        assertFalse(before.isClosed(), "the kept connection must still be open");
    }

    /**
     * The two sources are separate facts: an empty discovered snapshot removes
     * only what discovery added, never a configured peer.
     */
    @Test
    void anEmptySnapshotDoesNotCancelAConfiguredPeer() throws Exception {
        nodeB = startNode("");
        int bPort = nodeB.get(HttpServer.class).port();
        nodeA = startNode("127.0.0.1:" + bPort);
        assertTrue(awaitConnections(nodeA, 1, 8000), "a configured peer must dial by presence alone");

        PeerConnection before = nodeA.get(PeerHub.class).connections().get(0);
        nodeA.get(CloudEventBus.class).replaceDiscoveredPeers(List.of());
        Thread.sleep(500);

        List<PeerConnection> after = nodeA.get(PeerHub.class).connections();
        assertEquals(1, after.size(),
            "an empty discovered snapshot must not tear down a configured peer");
        assertSame(before, after.get(0), "the configured peer's connection must be untouched");
    }

    /**
     * A snapshot fed before {@code start()} is recorded, not dialed: nothing
     * may reach the network until the connector is started, which is what keeps
     * a built-but-unstarted connector inert. {@code start()} then dials it.
     */
    @Test
    void aSnapshotBeforeStartIsRecordedAndDialedByStart() throws Exception {
        nodeA = startNode("");
        awaitWired(nodeA);
        PeerConnector connector = new PeerConnector(nodeA.get(PeerHub.class),
            PeerConnector.Wiring.defaults().withBackoff(100, 300));
        try {
            // A port with nothing behind it: the dial fails fast, and the
            // failed attempt is the observable that a dial was even attempted.
            PeerAddress peer = PeerAddress.parse("127.0.0.1:" + freePort());
            connector.replaceDiscoveredPeers(List.of(peer.toString()));
            Thread.sleep(300);
            assertEquals(0, connector.backoffAttempts(peer),
                "nothing may dial before start()");

            connector.start(List.of());
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && connector.backoffAttempts(peer) == 0) {
                Thread.sleep(25);
            }
            assertTrue(connector.backoffAttempts(peer) >= 1,
                "start() must dial the snapshot recorded before it");
        } finally {
            connector.close();
        }
    }

        /**
     * The full discovery-fed story, end to end: an instance appears in a
     * directory the node asks (what an operator, another service, or a registry
     * node's client would write), the feeder hands it to the mesh, the node
     * dials it - and when it leaves the directory, the node drops it and never
     * dials it again. No static peers: the mesh configured itself.
     *
     * <p>Only A runs the feeder: the interval is a system property, and B is a
     * pure listener that never asks discovery for anything. That asymmetry is
     * what makes the removal assertion deterministic - no one re-dials B from
     * the other side.</p>
     */
    @Test
    void aDiscoveredInstanceIsDialedAndDroppedThroughTheFeed() throws Exception {
        // B first: it must start without the feed key.
        nodeB = startNode("");
        int bPort = nodeB.get(HttpServer.class).port();

        Map<String, ServiceInstance> directory = new ConcurrentHashMap<>();
        System.setProperty(ConfigKeys.EVENT_DISCOVERY_FEED_MS, "100");
        nodeA = startNode("", new StaticDiscovery(directory));
        awaitWired(nodeA);

        String serviceId = nodeA.get(PeerHub.class).serviceId();
        directory.put("b-1", ServiceInstance.of(serviceId, "b-1",
            Endpoint.of("http", "127.0.0.1", bPort)));

        assertTrue(awaitConnections(nodeA, 1, 5000),
            "an instance registered into the directory must be dialed - the mesh"
                + " configured itself without a static peer list");

        directory.remove("b-1");
        assertTrue(awaitConnections(nodeA, 0, 3000),
            "an instance that left the directory must be disconnected");
        Thread.sleep(600);
        assertTrue(nodeA.get(PeerHub.class).connections().isEmpty(),
            "the dropped instance must stay dropped - not re-dialed from a stale set");
    }

    /** A stand-in for any discovery backend: whatever an operator or another
     *  service wrote into the directory is what the node gets to see. */
    private record StaticDiscovery(Map<String, ServiceInstance> directory)
            implements ServiceDiscovery {
        @Override
        public List<ServiceInstance> instances(String serviceId) {
            return directory.values().stream()
                .filter(i -> i.serviceId().equals(serviceId))
                .toList();
        }
    }

    private static AppRuntime startNode(String peers,
                                        ServiceDiscovery discovery) {
        return FreewayApp.run(new ModuleEx[] {
            new HttpModule(), new CloudEventModule(),
            binder -> binder.bind(ServiceDiscovery.class).to(c -> discovery)});
    }

// ── helpers ───────────────────────────────────────────────────────────

    private static AppRuntime startNode(String peers) {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        System.setProperty(ConfigKeys.EVENT_ENABLED, "true");
        System.setProperty(ConfigKeys.EVENT_BACKOFF_BASE_MS, FAST_BACKOFF_BASE_MS);
        System.setProperty(ConfigKeys.EVENT_BACKOFF_MAX_MS, FAST_BACKOFF_MAX_MS);
        System.setProperty(ConfigKeys.EVENT_PEERS, peers);
        List<ModuleEx> modules = List.of(new HttpModule(), new CloudEventModule());
        return FreewayApp.run(modules.toArray(new ModuleEx[0]));
    }

    private static void awaitWired(AppRuntime app) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !app.get(PeerHub.class).wired()) {
            Thread.sleep(25);
        }
        assertTrue(app.get(PeerHub.class).wired(), "the mesh must be wired before peers are fed");
    }

    private static boolean awaitConnections(AppRuntime app, int count, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (app.get(PeerHub.class).connections().size() == count) {
                return true;
            }
            Thread.sleep(50);
        }
        // One last look: the sleep above may have carried us past the deadline by
        // less than the change we were waiting for.
        return app.get(PeerHub.class).connections().size() == count;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
