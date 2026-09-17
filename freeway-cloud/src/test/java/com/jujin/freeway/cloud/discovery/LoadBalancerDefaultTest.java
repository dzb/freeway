package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default balancer must read the metadata its own value type exposes:
 * {@link ServiceInstance#weight()} was documented and accessored but never
 * consulted — "configured but unread" — so every instance took equal turns
 * no matter what it declared.
 */
class LoadBalancerDefaultTest {

    private static ServiceInstance instance(String id, Map<String, String> meta) {
        return ServiceInstance.of("svc", id, Endpoint.of("http", "127.0.0.1", 8080), meta);
    }

    @Test
    void weightIsTheInstancesShareOfTheCycle() {
        var lb = new LoadBalancerDefault();
        var heavy = instance("heavy", Map.of("weight", "3"));
        var light = instance("light", Map.of());
        var instances = List.of(heavy, light);

        var hits = new HashMap<String, Integer>();
        for (int i = 0; i < 4; i++) {
            hits.merge(lb.choose(instances).orElseThrow().instanceId(), 1, Integer::sum);
        }
        assertEquals(3, hits.get("heavy"), "weight 3 of total 4 owns three slots per cycle");
        assertEquals(1, hits.get("light"));
    }

    @Test
    void zeroOrNegativeWeightStillTakesOneTurnPerCycle() {
        var lb = new LoadBalancerDefault();
        var zero = instance("zero", Map.of("weight", "0"));
        var normal = instance("normal", Map.of());
        var instances = List.of(zero, normal);

        var hits = new HashMap<String, Integer>();
        for (int i = 0; i < 2; i++) {
            hits.merge(lb.choose(instances).orElseThrow().instanceId(), 1, Integer::sum);
        }
        assertEquals(1, hits.get("zero"),
            "weight 0 means minimum share, not invisible — removal is deregistration");
        assertEquals(1, hits.get("normal"));
    }

    @Test
    void emptySetChoosesNobody() {
        assertTrue(new LoadBalancerDefault().choose(List.of()).isEmpty());
    }
}
