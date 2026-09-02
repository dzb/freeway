package com.jujin.freeway.cloud.events;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbound admission on the CloudEventBus: the mesh accepts frames from any
 * connected peer, so every channel needs a gate — and the gates must be
 * separate, because a CLASS-channel allowlist says nothing about TOPIC
 * payloads (the peer names the topic and supplies the body).
 */
class PeerHubInboundGateTest {

    private static CloudEventEnvelope.Parsed frame(EventSink.Channel channel, String type) {
        return new CloudEventEnvelope.Parsed(
            "id-1", "freeway://svc", type, null, "peer-a", channel, "\"payload\"");
    }

    private record Rig(PeerHub hub, EventBus bus, List<Object> inbound) {}

    private static Rig rig(List<String> allowedTypes, List<String> allowedTopics) {
        Container container = Freeway.create();
        EventBus bus = container.get(EventBus.class);
        List<Object> inbound = new ArrayList<>();
        bus.subscribe("greet.hello", inbound::add);
        bus.subscribe("other.topic", inbound::add);

        PeerHub hub = new PeerHub();
        hub.wire(new PeerHub.Wiring(bus, new JsonCodecDefault(), "svc", "inst-1",
            List.of("greet."), allowedTypes, allowedTopics, ""));
        return new Rig(hub, bus, inbound);
    }

    @Test
    void topicChannelIsGatedByTheTopicAllowlist() {
        Rig rig = rig(List.of(), List.of("greet."));

        rig.hub().receive(frame(EventSink.Channel.TOPIC, "other.topic"));
        assertTrue(rig.inbound().isEmpty(),
            "a topic outside the allowlist must not reach the local bus");

        rig.hub().receive(frame(EventSink.Channel.TOPIC, "greet.hello"));
        assertEquals1(rig.inbound(), "an allowlisted topic must be delivered");
    }

    @Test
    void classChannelIsGatedByTheTypeAllowlist() {
        Rig rig = rig(List.of("demo.Greeting"), List.of("greet."));

        // Not on this classpath at all — the allowlist must reject it before
        // any type resolution is attempted.
        rig.hub().receive(frame(EventSink.Channel.CLASS, "com.evil.Gadget"));
        assertTrue(rig.inbound().isEmpty(),
            "a class outside the allowlist must never be resolved");
    }

    @Test
    void emptyAllowlistIsStillAcceptAllOnBothChannels() {
        // Documented default: an empty list disables the gate. It warns loudly
        // at wire() time instead of silently tightening under an upgrade.
        Rig rig = rig(List.of(), List.of());
        rig.hub().receive(frame(EventSink.Channel.TOPIC, "other.topic"));
        assertEquals1(rig.inbound(), "empty allowlist = documented accept-all");
    }

    @Test
    void ownOriginIsNeverDispatched() {
        Rig rig = rig(List.of(), List.of());
        CloudEventEnvelope.Parsed looped = new CloudEventEnvelope.Parsed(
            "id-1", "freeway://svc", "greet.hello", null, "inst-1",
            EventSink.Channel.TOPIC, "\"payload\"");

        rig.hub().receive(looped);

        assertTrue(rig.inbound().isEmpty(), "our own event looped back must be dropped");
    }

    @Test
    void duplicateSimultaneousDialsKeepSingleConnectionByOriginOrder() {
        Container container = Freeway.create();
        PeerHub smallerHub = new PeerHub();
        smallerHub.wire(new PeerHub.Wiring(container.get(EventBus.class), new JsonCodecDefault(),
            "svc", "a-node", List.of(), List.of(), List.of(), ""));

        AtomicBoolean outboundClosed = new AtomicBoolean();
        AtomicBoolean inboundClosed = new AtomicBoolean();
        PeerConnection outbound = new PeerConnection("b-node", List.of(),
            s -> true, true, () -> outboundClosed.set(true));
        PeerConnection inbound = new PeerConnection("b-node", List.of(),
            s -> true, false, () -> inboundClosed.set(true));

        smallerHub.register(outbound);
        smallerHub.register(inbound);

        assertEquals(1, smallerHub.connections().size());
        assertFalse(outbound.isClosed(), "smaller origin keeps its outbound connection");
        assertTrue(inbound.isClosed(), "smaller origin closes the peer-initiated duplicate");
        assertEquals(outbound, smallerHub.connections().get(0));

        // Larger origin applies the mirror rule: keep the inbound connection.
        PeerHub largerHub = new PeerHub();
        largerHub.wire(new PeerHub.Wiring(container.get(EventBus.class), new JsonCodecDefault(),
            "svc", "z-node", List.of(), List.of(), List.of(), ""));
        AtomicBoolean largerOutboundClosed = new AtomicBoolean();
        AtomicBoolean largerInboundClosed = new AtomicBoolean();
        PeerConnection largerOutbound = new PeerConnection("a-node", List.of(),
            s -> true, true, () -> largerOutboundClosed.set(true));
        PeerConnection largerInbound = new PeerConnection("a-node", List.of(),
            s -> true, false, () -> largerInboundClosed.set(true));

        largerHub.register(largerInbound);
        largerHub.register(largerOutbound);

        assertEquals(1, largerHub.connections().size());
        assertFalse(largerInbound.isClosed(), "larger origin keeps the peer-initiated connection");
        assertTrue(largerOutbound.isClosed(), "larger origin closes its own outbound duplicate");
        assertEquals(largerInbound, largerHub.connections().get(0));
    }

    @Test
    void tokenGateAcceptsOnlyTheConfiguredSecret() {
        assertTrue(PeerHub.acceptsToken("anything", ""), "no token configured = no peer auth");
        assertTrue(PeerHub.acceptsToken(null, ""), "no token configured = no peer auth");
        assertTrue(PeerHub.acceptsToken("s3cret", "s3cret"), "matching token accepted");
        assertFalse(PeerHub.acceptsToken("s3cre", "s3cret"), "prefix must not pass");
        assertFalse(PeerHub.acceptsToken("S3CRET", "s3cret"), "comparison is case-sensitive");
        assertFalse(PeerHub.acceptsToken(null, "s3cret"), "absent token rejected");
        assertFalse(PeerHub.acceptsToken("", "s3cret"), "blank token rejected");
    }

    private static void assertEquals1(List<Object> actual, String message) {
        assertTrue(actual.size() == 1, message + " — got " + actual);
    }
}
