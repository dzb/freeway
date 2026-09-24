package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.Freeway;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trace continuity across the mesh: the sender's span must reach the
 * receiver's handlers, and a traceless frame must not disturb the receiver.
 */
class EventTraceTest {

    private static final TraceContext TRACE = new TraceContext(
        "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", null,
        "01", "rojo=00f067aa0ba902b7");
    private static final String TRACEPARENT =
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    /** Runs {@code work} with {@code TRACE} ambient, restoring the previous binding after. */
    private static void withAmbientTrace(Runnable work) {
        InvocationContext previous =
            InvocationContext.replaceAmbient(InvocationContext.of(TRACE, null, null));
        try {
            work.run();
        } finally {
            InvocationContext.replaceAmbient(previous);
        }
    }

    @Test
    void ambientTraceIsStampedOnOutboundFrames() {
        var codec = new JsonCodecDefault();

        String bare = CloudEventEnvelope.translate(
            "hi", "greet.hello", EventSink.Channel.TOPIC, "peer-a", "svc", codec, "id-1");
        assertFalse(bare.contains("traceparent"),
            "a traceless publish stays byte-identical to before: " + bare);

        var stamped = new AtomicReference<String>();
        withAmbientTrace(() -> stamped.set(CloudEventEnvelope.translate(
            "hi", "greet.hello", EventSink.Channel.TOPIC, "peer-a", "svc", codec, "id-1")));
        assertTrue(stamped.get().contains("\"traceparent\":\"" + TRACEPARENT + "\""),
            "the ambient span must ride the frame: " + stamped.get());
        assertTrue(stamped.get().contains("\"tracestate\":\"rojo=00f067aa0ba902b7\""),
            "vendor state must survive the hop: " + stamped.get());

        CloudEventEnvelope.Parsed parsed = CloudEventEnvelope.parse(stamped.get());
        assertEquals(TRACEPARENT, parsed.traceparent());
        assertEquals("rojo=00f067aa0ba902b7", parsed.tracestate());
    }

    @Test
    void inboundTraceIsRestoredAroundDispatch() {
        Container container = Freeway.create();
        EventBus bus = container.get(EventBus.class);
        var seen = new AtomicReference<Optional<InvocationContext>>();
        bus.subscribe("greet.hello", payload -> seen.set(InvocationContext.current()));

        PeerHub hub = new PeerHub();
        hub.wire(new PeerHub.Wiring(bus, new JsonCodecDefault(), "svc", "inst-1",
            List.of("greet."), List.of(), List.of(), ""));

        // Built through the real envelope path, under ambient trace — then the
        // ambient is cleared, so whatever the subscriber sees came off the wire.
        var wire = new AtomicReference<CloudEventEnvelope.Parsed>();
        withAmbientTrace(() -> wire.set(CloudEventEnvelope.parse(
            CloudEventEnvelope.translate("hi", "greet.hello", EventSink.Channel.TOPIC,
                "peer-a", "svc", new JsonCodecDefault(), "id-9"))));

        hub.receive(wire.get());

        InvocationContext restored = seen.get().orElseThrow(
            () -> new AssertionError("the subscriber never ran"));
        assertNotNull(restored.trace(), "the wire trace must be restored around dispatch");
        assertEquals("0af7651916cd43dd8448eb211c80319c", restored.trace().traceId());
        assertEquals(TRACEPARENT, restored.trace().toTraceparent());
        assertEquals("rojo=00f067aa0ba902b7", restored.trace().traceState());
        container.close();
    }

    @Test
    void tracelessFrameLeavesAmbientUntouched() {
        Container container = Freeway.create();
        EventBus bus = container.get(EventBus.class);
        var seen = new AtomicReference<Optional<InvocationContext>>();
        bus.subscribe("greet.hello", payload -> seen.set(InvocationContext.current()));

        PeerHub hub = new PeerHub();
        hub.wire(new PeerHub.Wiring(bus, new JsonCodecDefault(), "svc", "inst-1",
            List.of("greet."), List.of(), List.of(), ""));

        InvocationContext ambient =
            InvocationContext.of(new TraceContext(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null), null, null);
        InvocationContext previous = InvocationContext.replaceAmbient(ambient);
        try {
            hub.receive(new CloudEventEnvelope.Parsed(
                "id-1", "freeway://svc", "greet.hello", null, "peer-a",
                EventSink.Channel.TOPIC, "\"hi\"", null, null));
        } finally {
            InvocationContext.replaceAmbient(previous);
        }

        assertSame(ambient, seen.get().orElseThrow(
            () -> new AssertionError("the subscriber never ran")),
            "a traceless frame must not shadow the consumer thread's ambient");
        container.close();
    }

    @Test
    void malformedTraceparentRunsBare() {
        // Lenient where the wire is untrusted (mirrors TracePropagator): a bad
        // traceparent degrades to traceless delivery, never a failed dispatch.
        Container container = Freeway.create();
        EventBus bus = container.get(EventBus.class);
        var seen = new AtomicReference<Optional<InvocationContext>>();
        bus.subscribe("greet.hello", payload -> seen.set(InvocationContext.current()));

        PeerHub hub = new PeerHub();
        hub.wire(new PeerHub.Wiring(bus, new JsonCodecDefault(), "svc", "inst-1",
            List.of("greet."), List.of(), List.of(), ""));

        InvocationContext ambient =
            InvocationContext.of(new TraceContext(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null), null, null);
        InvocationContext previous = InvocationContext.replaceAmbient(ambient);
        try {
            hub.receive(new CloudEventEnvelope.Parsed(
                "id-1", "freeway://svc", "greet.hello", null, "peer-a",
                EventSink.Channel.TOPIC, "\"hi\"", "not-a-traceparent", null));
        } finally {
            InvocationContext.replaceAmbient(previous);
        }

        assertSame(ambient, seen.get().orElseThrow(
            () -> new AssertionError("the subscriber never ran")),
            "a malformed traceparent must degrade to bare delivery");
        container.close();
    }
}
