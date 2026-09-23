package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;

/**
 * Distributed tracer. Context is carried by {@code InvocationContext}
 * (ScopedValue) across async/virtual-thread boundaries; MDC is the display
 * layer only ({@code JULMDCAdapter} is ThreadLocal and does not propagate).
 */
public interface Tracer {

    /** Starts a span under the current invocation context (root when absent). */
    Span start(String name);

    /** Starts a span under an explicit parent context. */
    Span start(String name, TraceContext parent);

    Tracer NOOP = new Tracer() {
        @Override
        public Span start(String name) {
            return Span.NOOP;
        }

        @Override
        public Span start(String name, TraceContext parent) {
            return Span.NOOP;
        }
    };

    /** An active span; closed in the same scope it was started. */
    interface Span extends AutoCloseable {
        Span NOOP = new Span() {
            @Override
            public void addTag(String key, String value) {
            }

            @Override
            public void addError(Throwable t) {
            }

            @Override
            public void close() {
            }
        };

        /**
         * The invocation context this span establishes for its scope: the same
         * trace, this span as the current span, and the principal/baggage the
         * tracer inherited. A caller that owns a scope (the inbound tracing
         * filter, a background job) binds it with
         * {@link InvocationContext#runWith} so
         * everything inside that scope — including outbound propagation —
         * continues <em>this</em> span rather than its parent.
         *
         * @return the context to bind, or {@code null} when the span owns none
         *         (a no-op tracer)
         */
        default InvocationContext context() {
            return null;
        }

        void addTag(String key, String value);

        void addError(Throwable t);

        /** Nanoseconds elapsed since the span started. Live before
         *  {@link #close()}, frozen afterwards. */
        default long elapsedNanos() {
            return 0;
        }

        @Override
        void close();
    }
}
