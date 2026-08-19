package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.internal.MeterRegistryDefault;
import com.jujin.freeway.cloud.internal.TracerDefault;
import com.jujin.freeway.cloud.observe.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;

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
    void meterRegistryRecords() {
        MeterRegistryDefault reg = new MeterRegistryDefault();
        reg.counter("hits").increment();
        reg.counter("hits").increment(2.5);
        assertEquals(3.5, reg.counterValue("hits"));

        reg.timer("lat").record(Duration.ofMillis(100));
        assertEquals(1, reg.timerCount("lat"));
        assertTrue(reg.timerTotalSeconds("lat") >= 0.1);

        reg.gauge("depth", () -> 42.0);
        assertEquals(42.0, reg.gaugeValue("depth"));
    }

    @Test
    void prometheusTextRendersAllMeterTypes() {
        MeterRegistryDefault reg = new MeterRegistryDefault();
        reg.counter("hits").increment(2.0);
        reg.timer("lat").record(Duration.ofMillis(100));
        reg.gauge("depth", () -> 42.0);

        String text = reg.prometheusText();
        assertTrue(text.contains("# TYPE hits counter\nhits 2.0"));
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
        }
        assertNull(MDC.get("traceId"), "span close restores the pre-span MDC state");
        assertNull(MDC.get("spanId"));
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
