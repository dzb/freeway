package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.context.TraceContext;

import java.util.Map;

/**
 * W3C {@code traceparent} propagation. Injects the current trace as a
 * {@code traceparent} header; extracts a trace context from an inbound header.
 */
public final class TracePropagator implements Propagator {

    public static final String HEADER_TRACEPARENT = "traceparent";

    @Override
    public void inject(InvocationContext ctx, Map<String, String> headers) {
        TraceContext trace = ctx.trace();
        if (trace != null) {
            headers.put(HEADER_TRACEPARENT, trace.toTraceparent());
        }
    }

    @Override
    public InvocationContext extract(Map<String, String> headers) {
        TraceContext trace = TraceContext.fromTraceparent(headers.get(HEADER_TRACEPARENT)).orElse(null);
        return InvocationContext.of(trace, null, Baggage.empty());
    }
}
