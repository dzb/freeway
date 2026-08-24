package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.scoped.Defer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario-driven demonstrations for {@link CallBus#advise(CallAdvice)} —
 * cross-cutting call policies as executable documentation. Each scenario is
 * a policy teams actually ship once a request-reply channel exists.
 */
class CallBusAdviseTest {

    // ==================== 1. tracing + audit layering ====================

    /**
     * <b>Scenario: every call leaves a trace span and an audit line.</b>
     * Two advices are layered — tracing registered first (outermost), audit
     * second. The recorded order proves the chain contract: outer advice
     * starts before the inner one runs and finishes after it returns.
     * Handler failures flow through both, so the span sees the exception.
     */
    @Test
    void layeredTracingAndAuditSeeEveryCall() {
        var bus = new CallBus(Freeway.create());
        bus.register("billing", new Object() {
            public int invoice(String orderId) {
                if (orderId.equals("ord-bad")) throw new IllegalStateException("declined");
                return 100;
            }
        });

        record Span(String topic, String outcome, long micros) {}
        var spans = new CopyOnWriteArrayList<Span>();
        var events = new CopyOnWriteArrayList<String>();

        bus.advise(chain -> {                       // outermost: tracing
            events.add("trace-start:" + chain.topic());
            long t0 = System.nanoTime();
            try {
                Object r = chain.proceed();
                spans.add(new Span(chain.topic(), "ok", (System.nanoTime() - t0) / 1000));
                return r;
            } catch (Throwable e) {
                spans.add(new Span(chain.topic(), e.getClass().getSimpleName(),
                    (System.nanoTime() - t0) / 1000));
                throw e;
            }
        });
        bus.advise(chain -> {                       // inner: audit
            events.add("audit:" + chain.payload());
            return chain.proceed();
        });

        // Consumers see the advised chain through the ordinary typed proxy.
        BillingApi billing = bus.consumer("billing", BillingApi.class);
        assertEquals(100, billing.invoice("ord-1"));
        assertThrows(IllegalStateException.class,
            () -> billing.invoice("ord-bad"));

        // Layering: trace starts before audit for each call.
        assertEquals(List.of(
            "trace-start:billing.invoice",
            "audit:[ord-1]",
            "trace-start:billing.invoice",
            "audit:[ord-bad]"), events);
        assertEquals(2, spans.size());
        assertEquals("ok", spans.get(0).outcome());
        assertEquals("IllegalStateException", spans.get(1).outcome());
        assertTrue(spans.get(0).micros() >= 0);
    }

    interface BillingApi {
        int invoice(String orderId);
    }

    // ==================== 2. circuit breaker ====================

    /**
     * <b>Scenario: a flaky downstream must be shed, not hammered.</b> A
     * real breaker checks its state BEFORE spending the budget: while the
     * recorded-failure count is under threshold, calls pass through; once
     * open, every further call fails fast without reaching the provider.
     * (Getting this order backwards still "works" but lets the Nth failure
     * hammer the dead downstream one last time.)
     */
    @Test
    void openBreakerShedsCallsWithoutReachingProvider() {
        var bus = new CallBus(Freeway.create());
        var hits = new int[]{0};
        bus.register("fraud", new Object() {
            public boolean risky(long orderId) {
                hits[0]++;
                throw new IllegalStateException("downstream timeout");
            }
        });

        final int THRESHOLD = 3;
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        bus.advise(chain -> {
            if (failures.get() >= THRESHOLD) {          // open? fail fast.
                throw new IllegalStateException("circuit-open");
            }
            try {
                return chain.proceed();
            } catch (RuntimeException e) {              // record and rethrow.
                failures.incrementAndGet();
                throw e;
            }
        });
        FraudApi fraud = bus.consumer("fraud", FraudApi.class);

        for (int i = 0; i < THRESHOLD; i++) {           // budget spent here
            assertThrows(IllegalStateException.class, () -> fraud.risky(1L));
        }
        int hitsAfterBudget = hits[0];

        assertThrows(IllegalStateException.class, () -> fraud.risky(2L));
        assertEquals(hitsAfterBudget, hits[0],
            "an open breaker must stop reaching the provider");
        bus.close();
    }

    interface FraudApi {
        boolean risky(long orderId);
    }

    // ==================== 3. response cache ====================

    /**
     * <b>Scenario: reference data is called in a hot loop.</b> A caching
     * advice answers repeats without proceeding — note the advice returns a
     * VALUE, not a stage: short-circuiting lives in result space while the
     * bus keeps managing stages.
     */
    @Test
    void cacheAdviceAnswersWithoutTouchingProvider() {
        var bus = new CallBus(Freeway.create());
        var providerHits = new int[]{0};
        bus.register("fx", new Object() {
            public int rate(String pair) {
                providerHits[0]++;
                return 7;
            }
        });

        Map<List<Object>, Object> cache = new ConcurrentHashMap<>();
        bus.advise(chain -> cache.computeIfAbsent(
            List.of(chain.topic(), chain.payload()),
            k -> {
                try {
                    return chain.proceed();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }));

        for (int i = 0; i < 5; i++) {
            assertEquals(7, bus.call("fx.rate", List.of("usd-cny"))
                .toCompletableFuture().join());
        }
        assertEquals(1, providerHits[0], "four of five calls were cache hits");
        assertEquals(5, bus.stats().called());
        assertEquals(1, bus.stats().served(),
            "cache short-circuits must not count as served handler runs");
        bus.close();
    }

    // ==================== 4. dead-call translation ====================

    /**
     * <b>Scenario: an optional module is missing, but the caller expects an
     * answer, not an error.</b> An advice observes {@link DeadCallException}
     * bubbling out of the terminal link and translates it into a fallback
     * reply — per-policy degradation without touching any consumer.
     */
    @Test
    void adviceTranslatesDeadCallsIntoFallbackAnswers() {
        var bus = new CallBus(Freeway.create());   // no provider registered

        bus.advise(chain -> {
            try {
                return chain.proceed();
            } catch (DeadCallException e) {
                return "<unavailable:" + e.topic() + ">";
            }
        });

        assertEquals("<unavailable:reco.forUser>",
            bus.call("reco.forUser", List.of("u-1"))
                .toCompletableFuture().join());
        bus.close();
    }

    // ==================== 5. transaction timing ====================

    /**
     * <b>Scenario: audit advices must fire when the work happens.</b>
     * Dispatch is inline, so inside a {@code Defer} scope advice fires at
     * invocation — a call behaves like a method call, transaction or not.
     * (Side effects that must wait for commit are facts: publish them on
     * the EventBus, whose buffering drains post-commit.)
     */
    @Test
    void advicesFireAtInvocationTimeEvenInsideTransaction() {
        var bus = new CallBus(Freeway.create());
        bus.register("notify", new Object() {
            public void ping(String id) { /* handler */ }
        });
        var observed = new ArrayList<String>();
        bus.advise(chain -> {
            observed.add(chain.topic());
            return chain.proceed();
        });

        Defer.within(() -> {
            bus.call("notify.ping", List.of("ord-1"));
            assertEquals(List.of("notify.ping"), observed,
                "advice fires inline, not at commit");
        });
        bus.close();
    }

    // ==================== 6. checked advice failure ====================

    /**
     * <b>Scenario: the advice itself fails.</b> A throwing advice behaves
     * like a failing handler from the caller's perspective — checked types
     * arrive wrapped in {@code CompletionException}, keeping one uniform
     * unwrapping rule on the consumer side.
     */
    @Test
    void throwingAdviceFailsTheCallUniformly() throws Exception {
        var bus = new CallBus(Freeway.create());
        bus.advise(chain -> {
            throw new java.io.IOException("config missing");
        });
        bus.register("any", new Object() {
            public String go(String x) { return "never"; }
        });

        var cause = assertThrows(java.util.concurrent.CompletionException.class,
            () -> bus.call("any.go", List.of("x")).toCompletableFuture().join())
            .getCause();
        assertInstanceOf(java.io.IOException.class, cause);
        bus.close();
    }

    // ==================== 7. selector scoping ====================

    /**
     * <b>Scenario: a breaker belongs to one flaky mapping, not the whole
     * bus.</b> The selector overload mirrors {@code Advisor.wrap(selector,
     * advice)}: non-matching topics pass through untouched, so policies
     * stay scoped without filtering boilerplate inside the advice body.
     */
    @Test
    void selectorLimitsAdviceToMatchingTopicsOnly() {
        var bus = new CallBus(Freeway.create());
        bus.register("flaky", new Object() {
            public String go(String x) { return "flaky-ok"; }
        });
        bus.register("solid", new Object() {
            public String go(String x) { return "solid-ok"; }
        });

        var advised = new CopyOnWriteArrayList<String>();
        bus.advise(t -> t.startsWith("flaky."), chain -> {
            advised.add(chain.topic());
            return chain.proceed();
        });

        assertEquals("solid-ok", bus.consumer("solid", SolidApi.class).go("x"));
        assertEquals("flaky-ok", bus.consumer("flaky", FlakyApi.class).go("x"));

        assertEquals(List.of("flaky.go"), advised,
            "the advice must have wrapped only its selected topics");
    }

    interface SolidApi {
        String go(String x);
    }

    interface FlakyApi {
        String go(String x);
    }
}
