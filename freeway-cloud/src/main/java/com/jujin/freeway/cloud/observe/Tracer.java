package com.jujin.freeway.cloud.observe;

/**
 * Distributed tracer. Context is carried by {@code InvocationContext}
 * (ScopedValue) across async/virtual-thread boundaries; MDC is the display
 * layer only ({@code JULMDCAdapter} is ThreadLocal and does not propagate).
 */
public interface Tracer {

    /** Starts a span under the current invocation context (root when absent). */
    Span start(String name);

    /** Starts a span under an explicit parent context. */
    Span start(String name, com.jujin.freeway.cloud.context.TraceContext parent);

    Tracer NOOP = new Tracer() {
        @Override
        public Span start(String name) {
            return Span.NOOP;
        }

        @Override
        public Span start(String name, com.jujin.freeway.cloud.context.TraceContext parent) {
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

        void addTag(String key, String value);

        void addError(Throwable t);

        @Override
        void close();
    }
}
