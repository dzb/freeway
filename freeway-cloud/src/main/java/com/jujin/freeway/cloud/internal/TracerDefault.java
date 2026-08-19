package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.observe.Tracer;
import org.slf4j.MDC;

/**
 * Default {@link Tracer}: creates child spans under the current
 * {@link InvocationContext} (root when absent) and mirrors traceId/spanId
 * into SLF4J MDC for log correlation. MDC is display-only — context itself
 * travels via ScopedValue, never via the ThreadLocal MDC.
 */
public final class TracerDefault implements Tracer {

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
        return new MdcSpan(child);
    }

    private static final class MdcSpan implements Span {

        private final String previousTraceId;
        private final String previousSpanId;

        MdcSpan(TraceContext child) {
            this.previousTraceId = MDC.get("traceId");
            this.previousSpanId = MDC.get("spanId");
            MDC.put("traceId", child.traceId());
            MDC.put("spanId", child.spanId());
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
        public void close() {
            restore("traceId", previousTraceId);
            restore("spanId", previousSpanId);
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
