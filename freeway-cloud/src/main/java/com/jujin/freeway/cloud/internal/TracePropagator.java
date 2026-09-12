package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.context.TraceContext;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W3C Trace Context propagation: injects the current trace as the
 * {@code traceparent} and {@code tracestate} headers, and extracts both from an
 * inbound request. {@code tracestate} is treated as opaque vendor state — it is
 * passed through unchanged, because rewriting it would break the sampling and
 * routing decisions of the tracer that produced it.
 *
 * <p>Extraction is lenient where the wire is untrusted: an invalid
 * {@code tracestate} is dropped (with a debug log) rather than failing the
 * request, while locally constructed contexts reject it in the record's
 * constructor.</p>
 */
public final class TracePropagator implements Propagator {

    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_TRACESTATE = "tracestate";

    private static final Logger LOG = LoggerFactory.getLogger(TracePropagator.class);

    @Override
    public void inject(InvocationContext ctx, Map<String, String> headers) {
        TraceContext trace = ctx.trace();
        if (trace != null) {
            headers.put(HEADER_TRACEPARENT, trace.toTraceparent());
            if (!trace.traceState().isEmpty()) {
                headers.put(HEADER_TRACESTATE, trace.traceState());
            }
        }
    }

    @Override
    public InvocationContext extract(Map<String, String> headers) {
        TraceContext trace = TraceContext.fromTraceparent(headers.get(HEADER_TRACEPARENT))
            .map(t -> t.withTraceState(inboundTraceState(headers)))
            .orElse(null);
        // Unset baggage is null (not Baggage.empty()): the propagation filter
        // merges parts by "non-null wins", so a later propagator's baggage
        // must not be clobbered by an empty value here.
        return InvocationContext.of(trace, null, null);
    }

    /** The inbound {@code tracestate}, or {@code ""} when absent or malformed. */
    private static String inboundTraceState(Map<String, String> headers) {
        String state = headers.get(HEADER_TRACESTATE);
        if (state == null || state.isBlank()) {
            return "";
        }
        String trimmed = state.trim();
        if (!TraceContext.isValidTraceState(trimmed)) {
            LOG.debug("Dropping malformed tracestate header ({} chars)", trimmed.length());
            return "";
        }
        return trimmed;
    }
}
