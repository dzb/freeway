package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.resilience.ResilienceModule;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceEjectionTest {

    private static final long MILLIS = 1_000_000L;

    private static ServiceInstance instance(String id) {
        return ServiceInstance.of("svc", id, ServiceEndpoint.of("http", "10.0.0.1", 8080));
    }

    private static RpcException connectFailure() {
        return RpcException.connect("svc", new IOException("refused"));
    }

    private static InstanceEjection ejection(AtomicLong clock, int threshold, int baseSeconds,
                                             int maxSeconds, int percent) {
        return new InstanceEjection(true, threshold,
            Duration.ofSeconds(baseSeconds), Duration.ofSeconds(maxSeconds), percent,
            null, clock::get);
    }

    private static void fail(InstanceEjection ejection, ServiceInstance instance, int times) {
        for (int i = 0; i < times; i++) {
            ejection.onFailure("svc", instance, connectFailure());
        }
    }

    @Test
    void belowTheThresholdNothingIsEjected() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 3, 30, 300, 50);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));          // answer size 2: the 50% cap allows one
        fail(ejection, a, 2);

        assertEquals(List.of(a, b), ejection.filter("svc", List.of(a, b)));
    }

    @Test
    void theFilteredViewIsReusedWhileTheAnswerAndStateAreUnchanged() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 5, 30, 300, 50);
        var a = instance("a");
        var b = instance("b");
        var answer = List.of(a, b);

        var first = ejection.filter("svc", answer);
        fail(ejection, a, 1);                            // below the threshold: filtering cannot change
        var second = ejection.filter("svc", answer);

        assertSame(first, second, "a stable answer and state reuse the memoized view");
    }

    @Test
    void anEjectionInvalidatesTheCachedView() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 30, 300, 100);
        var a = instance("a");
        var b = instance("b");
        var answer = List.of(a, b);

        var before = ejection.filter("svc", answer);
        assertEquals(2, before.size());
        fail(ejection, a, 1);
        var after = ejection.filter("svc", answer);

        assertEquals(List.of(b), after);
        assertNotSame(before, after, "the ejection bumps the version: the old view is not reused");
    }

    @Test
    void anExpiredWindowInvalidatesTheCachedView() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 1, 300, 100);
        var a = instance("a");
        var b = instance("b");
        var answer = List.of(a, b);

        ejection.filter("svc", answer);
        fail(ejection, a, 1);
        assertEquals(List.of(b), ejection.filter("svc", answer));

        clock.set(1001 * MILLIS);                        // past the 1s window
        assertEquals(answer, ejection.filter("svc", answer),
            "the earliest window expiry forces a recompute even without a state write");
    }

    @Test
    void theThresholdEjectsAndTheFilterExcludesTheInstance() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 2, 30, 300, 50);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));          // answer size 2: the 50% cap allows one
        fail(ejection, a, 2);

        assertEquals(List.of(b), ejection.filter("svc", List.of(a, b)));
    }

    @Test
    void aSuccessResetsTheFailureStreak() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 3, 30, 300, 50);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));          // answer size 2: an ejection is observable
        fail(ejection, a, 2);
        ejection.onSuccess("svc", a);
        fail(ejection, a, 2);

        assertEquals(List.of(a, b), ejection.filter("svc", List.of(a, b)),
            "a success resets the streak: four failures split in two never reach the threshold");
    }

    @Test
    void theEjectionExpiresWithTheWindow() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 1, 300, 100);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));
        fail(ejection, a, 1);
        clock.set(999 * MILLIS);
        assertEquals(List.of(b), ejection.filter("svc", List.of(a, b)));
        clock.set(1001 * MILLIS);
        assertEquals(List.of(a, b), ejection.filter("svc", List.of(a, b)));
    }

    @Test
    void repeatedEjectionsDoubleTheWindowUpToTheCap() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 1, 4, 100);
        var a = instance("a");
        var b = instance("b");
        var answer = List.of(a, b);

        ejection.filter("svc", answer);
        fail(ejection, a, 1);                            // window 1s: out until 1000ms
        clock.set(1001 * MILLIS);
        fail(ejection, a, 1);                            // window 2s: out until 3001ms
        clock.set(3000 * MILLIS);
        assertEquals(List.of(b), ejection.filter("svc", answer), "2s window still running");
        clock.set(3002 * MILLIS);
        fail(ejection, a, 1);                            // window 4s: out until 7002ms
        clock.set(7001 * MILLIS);
        assertEquals(List.of(b), ejection.filter("svc", answer), "4s window still running");
        clock.set(7003 * MILLIS);
        fail(ejection, a, 1);                            // capped at 4s: out until 11003ms
        clock.set(11002 * MILLIS);
        assertEquals(List.of(b), ejection.filter("svc", answer), "the window is capped");
        clock.set(11004 * MILLIS);
        assertEquals(answer, ejection.filter("svc", answer));
    }

    @Test
    void thePercentGuardCapsEjectionsPerAnswer() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 30, 300, 50);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));          // answer size 2
        fail(ejection, a, 1);                            // 1 of 2 = 50%: allowed
        assertEquals(List.of(b), ejection.filter("svc", List.of(a, b)));

        fail(ejection, b, 1);                            // would be 2 of 2 > 50%: refused
        assertEquals(List.of(b), ejection.filter("svc", List.of(a, b)),
            "the second instance stays; the ejected one is still out");
    }

    @Test
    void aSingleInstanceAnswerAlwaysKeepsItsOnlyInstance() {
        var clock = new AtomicLong();
        var metrics = new CountingMetrics();
        var ejection = new InstanceEjection(true, 1,
            Duration.ofSeconds(30), Duration.ofSeconds(300), 50, metrics, clock::get);
        var vip = instance("vip");

        ejection.filter("svc", List.of(vip));
        fail(ejection, vip, 5);

        assertEquals(List.of(vip), ejection.filter("svc", List.of(vip)));
        assertEquals(0L, metrics.counts.getOrDefault("cloud.rpc.ejection.ejected", 0L),
            "the 50% guard suppresses ejection entirely for a single-instance answer");
    }

    @Test
    void whenEverythingIsEjectedTheFilterFallsBackToTheFullAnswer() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 30, 300, 100);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));
        fail(ejection, a, 1);
        fail(ejection, b, 1);

        assertEquals(List.of(a, b), ejection.filter("svc", List.of(a, b)),
            "a partial topology must not become an outage");
    }

    @Test
    void ejectionComposesWithZoneLocalPreference() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 30, 300, 100);
        var local = ServiceInstance.of("svc", "local",
            ServiceEndpoint.of("http", "10.0.0.1", 8080), Map.of("zone", "az-a"));
        var remote = ServiceInstance.of("svc", "remote",
            ServiceEndpoint.of("http", "10.0.0.2", 8080), Map.of("zone", "az-b"));
        var answer = List.of(local, remote);
        var balancer = new LoadBalancerDefault("az-a");

        ejection.filter("svc", answer);
        assertEquals("local",
            balancer.choose(ejection.filter("svc", answer)).orElseThrow().instanceId(),
            "zone-local preference still selects among the survivors");

        fail(ejection, local, 1);                        // threshold 1: the local member is out
        assertEquals("remote",
            balancer.choose(ejection.filter("svc", answer)).orElseThrow().instanceId(),
            "with no local survivor the balancer degrades cross-zone instead of failing");
    }

    @Test
    void onlyTheInstancesOwnWireFailuresQualify() {
        assertTrue(InstanceEjection.qualifies(connectFailure()));
        assertTrue(InstanceEjection.qualifies(
            RpcException.timeout("svc", new IOException("read timed out"))));
        assertTrue(InstanceEjection.qualifies(
            RpcException.transport("svc", new IOException("reset"))));
        assertTrue(InstanceEjection.qualifies(RpcException.http("svc", 500)));
        assertTrue(InstanceEjection.qualifies(RpcException.http("svc", 503)));

        assertFalse(InstanceEjection.qualifies(RpcException.timeout("svc")),
            "budget exhausted before the send: not the instance's fault");
        assertFalse(InstanceEjection.qualifies(RpcException.http("svc", 404)));
        assertFalse(InstanceEjection.qualifies(RpcException.http("svc", 429)),
            "peer backpressure is not an instance fault");
        assertFalse(InstanceEjection.qualifies(
            RpcException.business("boom", 400, new RuntimeException("x"))));
        assertFalse(InstanceEjection.qualifies(
            RpcException.rejected("version", 400, null)));
        assertFalse(InstanceEjection.qualifies(
            RpcException.unreadableReply("bad", new IllegalStateException("x"))));
        assertFalse(InstanceEjection.qualifies(
            RpcException.interrupted("svc", null)));
        assertFalse(InstanceEjection.qualifies(RpcException.rateLimited("svc")));
        assertFalse(InstanceEjection.qualifies(RpcException.circuitOpen("svc")));
        assertFalse(InstanceEjection.qualifies(RpcException.noInstance("svc")));
        assertFalse(InstanceEjection.qualifies(
            RpcException.dispatch("svc", new RuntimeException("x"))));
        assertFalse(InstanceEjection.qualifies(
            RpcException.of("other", true, 500, null)));
    }

    @Test
    void recoveryIsCountedAndResetsTheEjectionCount() {
        var clock = new AtomicLong();
        var metrics = new CountingMetrics();
        var ejection = new InstanceEjection(true, 1,
            Duration.ofSeconds(10), Duration.ofSeconds(40), 100, metrics, clock::get);
        var a = instance("a");
        var b = instance("b");
        var answer = List.of(a, b);

        ejection.filter("svc", answer);
        fail(ejection, a, 1);
        assertEquals(1L, metrics.counts.getOrDefault("cloud.rpc.ejection.ejected", 0L));

        ejection.onSuccess("svc", a);                    // recovered (e.g. via the fallback)
        assertEquals(1L, metrics.counts.getOrDefault("cloud.rpc.ejection.recovered", 0L));
        assertEquals(Map.of("service", "svc"),
            metrics.labels.get("cloud.rpc.ejection.ejected"),
            "ejection counters carry only the bounded service dimension");
        assertEquals(Map.of("service", "svc"),
            metrics.labels.get("cloud.rpc.ejection.recovered"));
        assertEquals(answer, ejection.filter("svc", answer));

        // The counter reset means the next ejection starts at the base window again.
        fail(ejection, a, 1);
        clock.set(9_000 * MILLIS);
        assertEquals(List.of(b), ejection.filter("svc", answer), "base window of 10s runs");
        clock.set(10_001 * MILLIS);
        assertEquals(answer, ejection.filter("svc", answer));
    }

    @Test
    void anInstanceThatLeavesTheAnswerIsEventuallyPruned() {
        var clock = new AtomicLong();
        var ejection = new InstanceEjection(true, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(4), 100, 3, null, clock::get);
        var instances = List.of(
            instance("i1"), instance("i2"), instance("i3"), instance("i4"), instance("i5"));

        ejection.filter("svc", instances);
        for (ServiceInstance instance : instances) {
            ejection.onFailure("svc", instance, connectFailure());
        }
        // All five are ejected, so the cap cannot evict any of them yet.
        assertEquals(5, ejection.trackedInstances("svc"));

        // Past 10 × max-window every expired entry is idle; the idle sweep must
        // remove them, not merely the cap-eviction fallback.
        clock.set(41_000 * MILLIS);
        ejection.onFailure("svc", instance("i6"), connectFailure());
        assertEquals(1, ejection.trackedInstances("svc"),
            "idle entries are pruned past 10 × max-window, leaving only the new instance: "
                + ejection.trackedInstances("svc"));
    }

    @Test
    void disabledPolicyNeverEjects() {
        var clock = new AtomicLong();
        var ejection = new InstanceEjection(false, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(1), 100, null, clock::get);
        var a = instance("a");

        fail(ejection, a, 10);

        assertEquals(List.of(a), ejection.filter("svc", List.of(a)));
        assertEquals(0, ejection.trackedInstances("svc"),
            "a disabled policy records no per-instance state");
    }

    @Test
    void configReadsTheDocumentedDefaults() {
        var ejection = InstanceEjection.from(symbols(Map.of()), null);
        var a = instance("a");
        var b = instance("b");

        ejection.filter("svc", List.of(a, b));          // answer size 2, the 50% default allows one
        fail(ejection, a, 4);
        assertEquals(List.of(a, b), ejection.filter("svc", List.of(a, b)),
            "four failures are below the default threshold of five");
        fail(ejection, a, 1);
        assertEquals(List.of(b), ejection.filter("svc", List.of(a, b)));
    }

    @Test
    void outOfRangeValuesFailNamingTheKey() {
        assertThrows(IllegalArgumentException.class, () -> InstanceEjection.from(symbols(Map.of(
            ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD, "0")), null));
        assertThrows(IllegalArgumentException.class, () -> InstanceEjection.from(symbols(Map.of(
            ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW, "0")), null));
        assertThrows(IllegalArgumentException.class, () -> InstanceEjection.from(symbols(Map.of(
            ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW, "30",
            ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_WINDOW, "10")), null));
        assertThrows(IllegalArgumentException.class, () -> InstanceEjection.from(symbols(Map.of(
            ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_PERCENT, "101")), null));
        assertThrows(IllegalArgumentException.class, () -> InstanceEjection.from(symbols(Map.of(
            ResilienceModule.ConfigKeys.RPC_RESILIENCE, "sometimes")), null));
    }

    @Test
    void theAggregateKillSwitchWinsOverTheFineGrainedKeys() {
        var ejection = InstanceEjection.from(symbols(Map.of(
            ResilienceModule.ConfigKeys.RPC_RESILIENCE,
            ResilienceModule.ConfigKeys.RPC_RESILIENCE_OFF,
            ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD, "0")), null);
        var a = instance("a");

        fail(ejection, a, 10);

        assertEquals(List.of(a), ejection.filter("svc", List.of(a)),
            "off disables ejection and ignores the fine-grained keys");
    }

    @Test
    void aCallerCancelDoesNotCountAgainstTheInstance() {
        var clock = new AtomicLong();
        var ejection = ejection(clock, 1, 30, 300, 100);
        var a = instance("a");

        ejection.onFailure("svc", a, RpcException.interrupted("svc", null));
        ejection.onFailure("svc", a, RpcException.rateLimited("svc"));

        assertEquals(List.of(a), ejection.filter("svc", List.of(a)));
        assertEquals(0, ejection.trackedInstances("svc"),
            "local rejections are not the instance's fault: no state is recorded");
    }

    private static SymbolSource symbols(Map<String, String> values) {
        Map<String, String> copy = new LinkedHashMap<>(values);
        return SymbolSource.of(new CoercerDefault(), SymbolProvider.of(() -> copy, 0));
    }

    /** Counts by name and records the labels of each meter's last use. */
    private static final class CountingMetrics implements com.jujin.freeway.commons.metrics.Metrics {
        final Map<String, Long> counts = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> labels = new ConcurrentHashMap<>();

        @Override
        public Counter counter(String name, Map<String, String> values) {
            labels.put(name, Map.copyOf(values));
            return counter(name);
        }

        @Override
        public Counter counter(String name) {
            return new Counter() {
                @Override
                public void increment() {
                    add(1);
                }

                @Override
                public void add(long delta) {
                    counts.merge(name, delta, Long::sum);
                }

                @Override
                public long value() {
                    return counts.getOrDefault(name, 0L);
                }
            };
        }

        @Override
        public void gauge(String name, java.util.function.Supplier<Number> supplier) {
            // not used by the ejection policy
        }
    }
}
