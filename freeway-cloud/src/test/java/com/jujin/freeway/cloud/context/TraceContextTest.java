package com.jujin.freeway.cloud.context;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3C traceparent encoding/decoding for {@link TraceContext}.
 */
class TraceContextTest {

    @Test
    void rootAndChild() {
        TraceContext root = TraceContext.root();
        TraceContext child = root.child();
        assertEquals(root.traceId(), child.traceId(), "child keeps the trace id");
        assertEquals(root.spanId(), child.parentSpanId(), "child links its parent span");
        assertNotEquals(root.spanId(), child.spanId(), "child gets a fresh span id");
    }

    @Test
    void w3cRoundTrip() {
        TraceContext ctx = TraceContext.root();
        String header = ctx.toTraceparent();
        assertTrue(header.startsWith("00-"));
        Optional<TraceContext> parsed = TraceContext.fromTraceparent(header);
        assertTrue(parsed.isPresent());
        assertEquals(ctx.traceId(), parsed.orElseThrow().traceId());
        assertEquals(ctx.spanId(), parsed.orElseThrow().spanId());
    }

    @Test
    void malformedHeadersRejected() {
        assertTrue(TraceContext.fromTraceparent(null).isEmpty());
        assertTrue(TraceContext.fromTraceparent("bogus").isEmpty());
        assertTrue(TraceContext.fromTraceparent("01-abc-123-01").isEmpty(), "unsupported version");
        assertTrue(TraceContext.fromTraceparent("00-tooshort-1234567890abcdef-01").isEmpty());
    }

    @Test
    void rejectsInvalidHexLengths() {
        assertThrows(IllegalArgumentException.class,
            () -> new TraceContext("abc", "1234567890abcdef", null), "traceId must be 32 hex");
        assertThrows(IllegalArgumentException.class,
            () -> new TraceContext("a".repeat(32), "short", null), "spanId must be 16 hex");
    }

    @Test
    void traceStateTravelsWithTheChildAndThroughConstruction() {
        TraceContext parent = TraceContext.root().withTraceState("vendor=abc");
        assertEquals("vendor=abc", parent.traceState());

        // W3C: tracestate belongs to the trace, not to one span.
        assertEquals("vendor=abc", parent.child().traceState());
        assertEquals(parent.traceId(), parent.child().traceId());

        // Omitted state is the empty string, never null.
        assertEquals("", TraceContext.root().traceState());
        assertEquals("", new TraceContext(parent.traceId(), parent.spanId(), null).traceState());
    }

    @Test
    void traceStateThatCouldBreakAHeaderIsRejected() {
        // A CR/LF here would be header injection on the next hop; an oversized
        // value is a protocol violation. Local construction is strict, inbound
        // extraction drops instead (TracePropagator).
        assertThrows(IllegalArgumentException.class,
            () -> TraceContext.root().withTraceState("vendor=abc\r\nX-Evil: 1"));
        assertThrows(IllegalArgumentException.class,
            () -> TraceContext.root().withTraceState("x".repeat(513)));
        assertThrows(IllegalArgumentException.class,
            () -> TraceContext.root().withTraceState("vendor=\u00e9"));

        assertTrue(TraceContext.isValidTraceState(""));
        assertTrue(TraceContext.isValidTraceState("vendor=abc,v=1"));
        assertFalse(TraceContext.isValidTraceState("bad\u0007"));
    }

    @Test
    void flagsRoundTripThroughTheHeader() {
        // Regression: inbound flags were validated and then dropped, and
        // outbound always wrote "-01" — a peer's sampling decision never
        // survived the hop. Flags now travel with the context.
        TraceContext parsed = TraceContext.fromTraceparent(
            "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-00").orElseThrow();
        assertEquals("00", parsed.flags(), "the sampled flag must survive extraction");

        String header = parsed.toTraceparent();
        assertTrue(header.endsWith("-00"), "outbound keeps the extracted flags: " + header);
        assertEquals(parsed, TraceContext.fromTraceparent(header).orElseThrow(),
            "encode → decode round-trips the full context");

        assertEquals("01", TraceContext.root().flags(), "fresh spans default to sampled");
        assertEquals(parsed.flags(), parsed.child().flags(),
            "a child span inherits the trace flags");
    }
}
