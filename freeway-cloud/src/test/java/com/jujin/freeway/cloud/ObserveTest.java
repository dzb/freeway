package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.internal.MetricsDefault;
import com.jujin.freeway.cloud.internal.TracePropagator;
import com.jujin.freeway.cloud.internal.TracerDefault;
import com.jujin.freeway.cloud.observe.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
