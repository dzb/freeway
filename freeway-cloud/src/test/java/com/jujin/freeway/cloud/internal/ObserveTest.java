package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.PrincipalContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.observe.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observability defaults: in-memory metrics recording and MDC mirroring of
 * trace context (display layer only).
 */
class ObserveTest {

    @Test
    void metricsRegistryRecords() {
        MetricsDefault reg = new MetricsDefault();
        reg.counter("hits").increment();
        reg.counter("hits").add(2);
        assertEquals(3, reg.counterValue("hits"));

        reg.timer("lat").record(Duration.ofMillis(100));
        assertEquals(1, reg.timerCount("lat"));
        assertTrue(reg.timerTotalSeconds("lat") >= 0.1);

        reg.gauge("depth", () -> 42);
        assertEquals(42.0, reg.gaugeValue("depth"));
    }

    @Test
    void prometheusTextRendersAllMeterTypes() {
        MetricsDefault reg = new MetricsDefault();
        reg.counter("hits").add(2);
        reg.timer("lat").record(Duration.ofMillis(100));
        reg.gauge("depth", () -> 42);

        String text = reg.prometheusText();
        assertTrue(text.contains("# TYPE hits counter\nhits 2"));
        assertTrue(text.contains("# TYPE lat_count counter\nlat_count 1"));
        assertTrue(text.contains("# TYPE lat_seconds_total counter\nlat_seconds_total 0.1"));
        assertTrue(text.contains("# TYPE depth gauge\ndepth 42.0"));
    }

    @Test
    void tracerMirrorsMdcAndRestores() {
        TracerDefault tracer = new TracerDefault();
        try (Tracer.Span span = tracer.start("op")) {
            assertNotNull(MDC.get("traceId"));
            assertNotNull(MDC.get("spanId"));
            assertEquals("op", MDC.get("spanName"));
        }
        assertNull(MDC.get("traceId"), "span close restores the pre-span MDC state");
        assertNull(MDC.get("spanId"));
        assertNull(MDC.get("spanName"), "span close restores the pre-span spanName state");
    }

    @Test
    void spanTracksElapsedDurationAndRecordsItIntoMetrics() throws Exception {
        MetricsDefault reg = new MetricsDefault();
        TracerDefault tracer = new TracerDefault(reg);
        Tracer.Span span = tracer.start("op");
        long live = span.elapsedNanos();
        Thread.sleep(5); // widen the window so elapsed is unambiguous
        assertTrue(span.elapsedNanos() >= live, "elapsed grows while open");
        span.close();

        assertTrue(span.elapsedNanos() >= 5_000_000,
            "duration frozen on close: " + span.elapsedNanos());
        long before = span.elapsedNanos();
        Thread.sleep(2);
        assertEquals(before, span.elapsedNanos(), "frozen after close");
        assertEquals(1, reg.timerCount("tracer.span.duration"),
            "closed spans are recorded into the metrics registry");
    }

    @Test
    void spanDurationIsAvailableWithoutMetricsWiring() throws Exception {
        TracerDefault tracer = new TracerDefault(); // no registry
        Tracer.Span span = tracer.start("op");
        Thread.sleep(2);
        span.close();
        assertTrue(span.elapsedNanos() >= 2_000_000,
            "duration tracking works without a metrics registry: " + span.elapsedNanos());
    }

    @Test
    void nestedSpansChainWithoutScopeAndBindContext() {
        TracerDefault tracer = new TracerDefault();
        try (Tracer.Span outer = tracer.start("outer")) {
            TraceContext outerCtx = InvocationContext.current().orElseThrow().trace();
            assertEquals(outerCtx.traceId(), MDC.get("traceId"));
            try (Tracer.Span inner = tracer.start("inner")) {
                TraceContext innerCtx = InvocationContext.current().orElseThrow().trace();
                assertEquals(outerCtx.traceId(), innerCtx.traceId(),
                    "nested spans share the trace instead of becoming sibling roots");
                assertEquals(outerCtx.spanId(), innerCtx.parentSpanId(),
                    "nested spans chain parent → child");
                // Outbound propagation must see the ambient context (the same
                // lookup CloudHttpClient uses before injecting traceparent).
                Map<String, String> headers = new HashMap<>();
                new TracePropagator().inject(InvocationContext.current().orElseThrow(), headers);
                assertTrue(headers.containsKey(TracePropagator.HEADER_TRACEPARENT),
                    "outbound injection sees the active trace outside any scoped binding");
            }
        }
        assertNull(MDC.get("traceId"), "MDC restored after nested spans close");
        assertTrue(InvocationContext.current().isEmpty(), "ambient context cleared on close");
    }

    @Test
    void invocationContextPropagatesWithinScope() {
        TraceContext trace = TraceContext.root();
        InvocationContext ic = InvocationContext.of(trace, null, null);
        InvocationContext.runWith(ic, () -> {
            assertEquals(trace, InvocationContext.current().orElseThrow().trace());
        });
        assertTrue(InvocationContext.current().isEmpty(), "context not visible outside the scope");
    }

    @Test
    void outOfOrderSpanClosesLeaveNoStaleContext() {
        // Regression: spans restored the snapshot captured at start, so an
        // out-of-order close sequence left a CLOSED span's context installed
        // on the thread forever — the next task on the pooled thread (and its
        // outbound calls) inherited a dead trace/baggage. Closes now recompute
        // the ambient from the live span stack.
        TracerDefault tracer = new TracerDefault();
        Tracer.Span outer = tracer.start("outer");
        Tracer.Span inner = tracer.start("inner");

        outer.close(); // out of order — inner is still open
        assertEquals("inner", MDC.get("spanName"),
            "the innermost still-open span owns the MDC state");
        assertTrue(InvocationContext.current().isPresent(),
            "the ambient stays on the open span");
        inner.close();
        assertTrue(InvocationContext.current().isEmpty(),
            "the last close restores the pre-span ambient");
        assertNull(MDC.get("traceId"), "full unwind restores the pre-span MDC");
        assertNull(MDC.get("spanName"));
    }

    @Test
    void spanStartInheritsAmbientBaggageAndPrincipal() {
        // Regression: starting a span replaced the ambient with an empty
        // baggage and no principal, silently dropping identity/business
        // context for the span's duration (and for outbound propagation).
        Baggage baggage = Baggage.of(Map.of("tenant", "acme"));
        InvocationContext prior = InvocationContext.of(
            null, PrincipalContext.of("alice", java.util.List.of()), baggage);
        TracerDefault tracer = new TracerDefault();
        InvocationContext.runWith(prior, () -> {
            try (Tracer.Span span = tracer.start("op")) {
                InvocationContext current = InvocationContext.current().orElseThrow();
                assertEquals("acme", current.baggage().get("tenant"),
                    "starting a span must not drop ambient baggage");
                assertEquals("alice", current.principal().name(),
                    "starting a span must not drop the ambient principal");
            }
        });
    }

    @Test
    void metricNamesThatRenderIdenticallyAreRejected() {
        // Regression: prometheusName folds '.', '-', '/' onto '_', so
        // differently-named metrics could render duplicate series — a scrape
        // the Prometheus parser rejects wholesale. Registration now fails fast.
        MetricsDefault reg = new MetricsDefault();
        reg.counter("a.b").increment();
        assertThrows(IllegalArgumentException.class, () -> reg.counter("a-b"),
            "sanitized-name collisions within a kind must fail fast");
        assertThrows(IllegalArgumentException.class, () -> reg.gauge("a/b", () -> 1),
            "counter/gauge share the plain-series namespace");
        assertThrows(IllegalArgumentException.class, () -> reg.gauge("a.b", () -> 1),
            "same raw name, different kind — still one plain series");
        reg.timer("a.b").record(Duration.ofMillis(1)); // timers render suffixed series — no collision
        assertThrows(IllegalArgumentException.class, () -> reg.timer("a-b"),
            "sanitized-name collisions within timers must fail fast");

        MetricsDefault fresh = new MetricsDefault();
        fresh.gauge("depth", () -> 1);
        fresh.gauge("depth", () -> 2); // gauge refresh is not a collision

        // A plain meter whose name collides with a timer's rendered suffix
        // must also fail fast: timer("lat") emits lat_count and
        // lat_seconds_total, so a later counter of either name duplicates a
        // sample and invalidates the scrape.
        MetricsDefault suffixed = new MetricsDefault();
        suffixed.timer("lat").record(Duration.ofMillis(1));
        assertThrows(IllegalArgumentException.class,
            () -> suffixed.counter("lat_seconds_total"),
            "timer suffix collisions with a counter must fail fast");
        assertThrows(IllegalArgumentException.class,
            () -> suffixed.counter("lat_count"),
            "timer count-series collisions with a counter must fail fast");

        // A timer registers two suffix series; when the second collides, the
        // first must not be left behind as a stale ownership claim.
        MetricsDefault rollback = new MetricsDefault();
        rollback.counter("foo_seconds_total").increment();
        assertThrows(IllegalArgumentException.class, () -> rollback.timer("foo"),
            "timer suffix collision must fail fast");
        rollback.counter("foo_count").increment(); // must not be blocked by the failed timer
    }
}
