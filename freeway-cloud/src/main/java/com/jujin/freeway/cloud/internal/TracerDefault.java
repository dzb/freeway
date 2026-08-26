package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.observe.MeterRegistry;
import com.jujin.freeway.cloud.observe.Tracer;
import org.slf4j.MDC;

import java.time.Duration;

/**
 * Default {@link Tracer}: creates child spans under the current
 * {@link InvocationContext} (root when absent) and mirrors traceId/spanId
 * into SLF4J MDC for log correlation. MDC is display-only — context itself
 * travels via ScopedValue, never via the ThreadLocal MDC.
 *
 * <p>Spans started outside any structured scope additionally bind their child
 * context as the thread's {@linkplain InvocationContext#replaceAmbient ambient
 * fallback} (restored on close). Nested {@code start()} calls then chain
 * parent → child instead of producing sibling roots, and outbound propagation
 * ({@code CloudHttpClient}) sees the active trace on such threads. The ambient
 * tier is same-thread only; cross-thread propagation still requires
 * {@code PropagationFilter}/{@code runWith}.
 *
 * <p>When a {@link MeterRegistry} is wired (standard assembly), each closed
 * span records its wall duration into the {@code tracer.span.duration} timer,
 * so span latency is visible on {@code /metrics} without an ext backend.
 * Tags and error details remain backend-tracer concerns (ext).
 */
public final class TracerDefault implements Tracer {

    private static final String SPAN_DURATION_TIMER = "tracer.span.duration";

    private final MeterRegistry metrics;

    public TracerDefault() {
        this(null);
    }

    /** @param metrics nullable — without it spans still carry
     *  {@link Span#elapsedNanos()} but nothing is exported. */
    public TracerDefault(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public Span start(String name) {
        TraceContext parent = InvocationContext.current()
            .map(InvocationContext::trace)
            .orElseGet(TraceContext::root);
        return start(name, parent);
    }

    @Override
    public Span start(String name, TraceContext parent) {
        TraceContext child = parent.child();
        return new MdcSpan(child, metrics);
    }

    private static final class MdcSpan implements Span {

        private final String previousTraceId;
        private final String previousSpanId;
        private final InvocationContext previousAmbient;
        private final MeterRegistry metrics;
        private final long startNanos = System.nanoTime();
        // Frozen by close(); elapsedNanos() reads live before that.
        private volatile long durationNanos = -1;

        MdcSpan(TraceContext child, MeterRegistry metrics) {
            this.previousTraceId = MDC.get("traceId");
            this.previousSpanId = MDC.get("spanId");
            this.metrics = metrics;
            MDC.put("traceId", child.traceId());
            MDC.put("spanId", child.spanId());
            // Bind the child so nested start()/outbound injection discover it.
            // Save/restore keeps out-of-order close sequences correct.
            this.previousAmbient = InvocationContext.replaceAmbient(
                InvocationContext.of(child, null, Baggage.empty()));
        }

        @Override
        public void addTag(String key, String value) {
            // Tags are captured by the backend tracer (ext); the local
            // default has nowhere to store them beyond MDC display.
        }

        @Override
        public void addError(Throwable t) {
            // Log correlation only; error capture is a backend concern.
        }

        @Override
        public long elapsedNanos() {
            long frozen = durationNanos;
            return frozen >= 0 ? frozen : System.nanoTime() - startNanos;
        }

        @Override
        public void close() {
            if (durationNanos < 0) {
                durationNanos = System.nanoTime() - startNanos;
                if (metrics != null) {
                    metrics.timer(SPAN_DURATION_TIMER)
                        .record(Duration.ofNanos(durationNanos));
                }
            }
            restore("traceId", previousTraceId);
            restore("spanId", previousSpanId);
            InvocationContext.replaceAmbient(previousAmbient); // null clears
        }

        private static void restore(String key, String previous) {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }
}
