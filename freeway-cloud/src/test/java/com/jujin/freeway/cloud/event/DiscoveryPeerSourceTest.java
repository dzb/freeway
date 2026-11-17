package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.event.internal.DiscoveryPeerSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source's contract with the bus is the snapshot: every tick hands over the
 * whole answer minus this node, a failed lookup hands over nothing (the mesh
 * keeps its previous set — one failed poll must not read as "everyone left"),
 * and close() ends the ticker.
 */
class DiscoveryPeerSourceTest {

    private static ServiceInstance instance(String id, String host, int port) {
        return ServiceInstance.of("svc", id, ServiceEndpoint.of("http", host, port));
    }

    @Test
    void feedsDiscoveredInstancesExcludingSelf() {
        List<ServiceInstance> answer = List.of(
            instance("self", "10.0.0.1", 8080),
            instance("b", "10.0.0.2", 8080),
            instance("c", "10.0.0.3", 8081));
        List<List<String>> fed = new ArrayList<>();
        DiscoveryPeerSource source = new DiscoveryPeerSource(
            serviceId -> answer, "svc", "self", fed::add, 1000);

        source.pollOnce();

        assertEquals(List.of("10.0.0.2:8080", "10.0.0.3:8081"), fed.get(0),
            "the node never dials itself; a peer is the registered endpoint's host:port");
    }

    @Test
    void aFailedLookupKeepsThePreviousSetAndRecoveryResumes() {
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> fed = new ArrayList<>();
        DiscoveryPeerSource source = new DiscoveryPeerSource(
            serviceId -> {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalStateException("discovery down");
                }
                return List.of(instance("b", "10.0.0.2", 8080));
            },
            "svc", "self", fed::add, 1000);

        source.pollOnce();  // answer: [b]
        source.pollOnce();  // discovery outage
        source.pollOnce();  // recovered

        assertEquals(2, fed.size(), "the failed poll must hand over nothing");
        assertEquals(List.of("10.0.0.2:8080"), fed.get(0), "before the outage");
        assertEquals(List.of("10.0.0.2:8080"), fed.get(1), "recovery hands a fresh snapshot");
    }

    @Test
    void anEmptyAnswerClearsTheSet() {
        List<List<String>> fed = new ArrayList<>();
        DiscoveryPeerSource source = new DiscoveryPeerSource(
            serviceId -> List.of(), "svc", "self", fed::add, 1000);

        source.pollOnce();

        assertEquals(List.of(), fed.get(0),
            "an empty answer is a real answer — everyone left and the set must follow");
    }

    @Test
    void theTickerFeedsUntilClosed() throws Exception {
        Map<String, ServiceInstance> directory = new ConcurrentHashMap<>();
        directory.put("b", instance("b", "10.0.0.2", 8080));
        ServiceDiscovery discovery = serviceId -> List.copyOf(directory.values());
        AtomicInteger feeds = new AtomicInteger();
        DiscoveryPeerSource source = new DiscoveryPeerSource(
            discovery, "svc", "self", frames -> feeds.incrementAndGet(), 50);

        source.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && feeds.get() < 3) {
            Thread.sleep(10);
        }
        assertTrue(feeds.get() >= 3, "the ticker must feed repeatedly, got " + feeds.get());

        source.close();
        int afterClose = feeds.get();
        Thread.sleep(200);
        assertEquals(afterClose, feeds.get(), "close() must stop the ticker");
    }
}
