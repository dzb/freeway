package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.internal.TracePropagator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Distributed-trace coupling for the event mesh: stamps the current trace onto
 * outbound envelopes and restores the inbound trace around local dispatch, so
 * an event crossing a JVM boundary does not break the causal chain.
 *
 * <p>Both directions reuse {@link TracePropagator} — the same inject/extract
 * the HTTP path uses — instead of re-implementing W3C parsing. The carrier
 * differs per transport (JSON frame extensions on the mesh, {@code ce-}
 * headers on Kafka), so this helper speaks plain header maps in both
 * directions and each transport copies them into its own envelope.
 *
 * <p>Trace (and its vendor state) only — never principal, never baggage.
 * Those are established by authenticated filters on the HTTP path; a
 * traceparent asserts causality ("this happened after that"), while a
 * principal asserts identity ("I am this user") — and nothing on the event
 * path authenticates the producer, so only the causal claim is honored here.
 *
 * <p>Public because transports live elsewhere (the Kafka adapter reads it
 * from another module); the methods are static and stateless.
 */
public final class EventTrace {

    /** The distributed-tracing extension attributes (CloudEvents extension names). */
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private static final TracePropagator TRACE = new TracePropagator();

    private EventTrace() {}

    /**
     * The current trace as header entries ({@code traceparent}, plus
     * {@code tracestate} when the context carries vendor state) — empty when
     * no trace is bound, in which case the caller stamps nothing.
     */
    public static Map<String, String> injectCurrent() {
        Map<String, String> headers = new LinkedHashMap<>();
        InvocationContext.current().ifPresent(ctx -> TRACE.inject(ctx, headers));
        return headers;
    }

    /**
     * Runs {@code work} under the trace carried by {@code headers} (W3C
     * {@code traceparent}, {@code tracestate} restored alongside). A missing
     * or unparseable trace runs the work directly: a traceless event must not
     * clear whatever ambient context the consuming thread already holds
     * (pooled poll threads outlive any single event).
     */
    public static void runWithTrace(Map<String, String> headers, Runnable work) {
        InvocationContext extracted = TRACE.extract(headers);
        if (extracted.trace() == null) {
            work.run();
            return;
        }
        InvocationContext.runWith(extracted, work);
    }
}
