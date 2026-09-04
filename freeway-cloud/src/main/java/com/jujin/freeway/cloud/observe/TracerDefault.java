package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.commons.metrics.Metrics;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayDeque;
import com.jujin.freeway.cloud.internal.PropagationFilter;

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
 * <p><b>Restore semantics.</b> A span must not restore the snapshot it saved
 * at start: under out-of-order closes that resurrects a stale context on
 * pooled threads (an inner span's close would re-install an outer span's
 * child that already closed, and the thread keeps the dead trace forever).
 * Closes instead recompute the ambient from the thread's live span stack —
 * the innermost still-open span wins, and once the stack is empty the
 * pre-span ambient is put back. The stack is per-thread, so no locking is
 * needed beyond the ThreadLocal itself.
 *
 * <p>When a {@link Metrics} registry is wired (standard assembly), each closed
 * span records its wall duration into the {@code tracer.span.duration} timer,
 * so span latency is visible on {@code /metrics} without an ext backend.
 * Tags and error details remain backend-tracer concerns (ext).
 */
public final class TracerDefault implements Tracer {

    private static final String SPAN_DURATION_TIMER = "tracer.span.duration";

    /** Per-thread open-span stack with the pre-span ambient/MDC snapshots. */
    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    private final Metrics metrics;

    public TracerDefault() {
        this(null);
    }

    /** @param metrics nullable — without it spans still carry
     *  {@link Span#elapsedNanos()} but nothing is exported. */
    public TracerDefault(Metrics metrics) {
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
        // The child inherits the ambient tier's principal and baggage so
        // starting a span never drops identity/business context; only the
        // trace is replaced by the child.
        InvocationContext ambient = InvocationContext.current().orElse(null);
        InvocationContext childContext = InvocationContext.of(
            child,
            ambient == null ? null : ambient.principal(),
            ambient == null ? Baggage.empty() : ambient.baggage());
        return new MdcSpan(name, childContext, metrics);
    }

    private static final class Frame {
        final ArrayDeque<ActiveSpan> stack = new ArrayDeque<>();
        /** Ambient/MDC state before the outermost span bound its child. */
        InvocationContext baseAmbient;
        String baseTraceId;
        String baseSpanId;
        String baseSpanName;
        boolean captured;
    }

    private record ActiveSpan(InvocationContext context, String traceId, String spanId, String name) {}

    private final class MdcSpan implements Span {

        private final ActiveSpan active;
        private final Metrics metrics;
        /** The frame of the thread that opened this span. A span may be closed
         *  on a different thread (e.g. an async continuation); restoring must
         *  target the owning thread's stack, not the closing thread's, or the
         *  owner's span stack is left with a stale entry and its MDC/diagId
         *  drift afterwards. */
        private final Frame frame;
        private final long startNanos = System.nanoTime();
        // Frozen by close(); elapsedNanos() reads live before that.
        private volatile long durationNanos = -1;

        MdcSpan(String name, InvocationContext childContext, Metrics metrics) {
            this.active = new ActiveSpan(
                childContext,
                childContext.trace().traceId(),
                childContext.trace().spanId(),
                name == null ? "" : name);
            this.metrics = metrics;
            Frame frame = FRAME.get();
            this.frame = frame;
            if (!frame.captured) {
                // First span on this thread — snapshot the ambient tier and
                // MDC so a full unwind can restore exactly what was there.
                frame.captured = true;
                frame.baseAmbient = InvocationContext.replaceAmbient(childContext);
                frame.baseTraceId = MDC.get("traceId");
                frame.baseSpanId = MDC.get("spanId");
                frame.baseSpanName = MDC.get("spanName");
            } else {
                InvocationContext.replaceAmbient(childContext);
            }
            frame.stack.addLast(active);
            MDC.put("traceId", active.traceId());
            MDC.put("spanId", active.spanId());
            MDC.put("spanName", active.name());
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
            restoreThreadState();
        }

        /**
         * Recomputes the thread's ambient context and MDC from the live
         * span stack after removing this span. Out-of-order closes converge:
         * an inner span's close keeps the (still-open) outermost span's
         * context, and the last close restores the pre-span snapshots.
         */
        private void restoreThreadState() {
            Frame frame = this.frame;
            if (!frame.stack.remove(active)) {
                return; // already closed — never restore twice
            }
            ActiveSpan top = frame.stack.peekLast();
            if (top == null) {
                InvocationContext.replaceAmbient(frame.baseAmbient);
                restore("traceId", frame.baseTraceId);
                restore("spanId", frame.baseSpanId);
                restore("spanName", frame.baseSpanName);
                frame.captured = false;
                FRAME.remove();
            } else {
                InvocationContext.replaceAmbient(top.context());
                MDC.put("traceId", top.traceId());
                MDC.put("spanId", top.spanId());
                MDC.put("spanName", top.name());
            }
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
