package com.jujin.freeway.cloud.events;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBridge;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbound admission on the CloudEventBus: the mesh accepts frames from any
 * connected peer, so every channel needs a gate — and the gates must be
 * separate, because a CLASS-channel allowlist says nothing about TOPIC
 * payloads (the peer names the topic and supplies the body).
 */
class PeerHubInboundGateTest {

    private static CloudEventEnvelope.Parsed frame(EventBridge.Channel channel, String type) {
        return new CloudEventEnvelope.Parsed(
            "id-1", "freeway://svc", type, null, "peer-a", channel, 1, "\"payload\"");
    }

    private record Rig(PeerHub hub, EventBus bus, List<Object> inbound) {}

    private static Rig rig(List<String> allowedTypes, List<String> allowedTopics) {
        Container container = Freeway.create();
        EventBus bus = container.get(EventBus.class);
        List<Object> inbound = new ArrayList<>();
        bus.subscribe("greet.hello", inbound::add);
        bus.subscribe("other.topic", inbound::add);

        PeerHub hub = new PeerHub();
        hub.wire(bus, new JsonCodecDefault(), "svc", "inst-1",
            List.of("greet."), allowedTypes, allowedTopics, "");
        return new Rig(hub, bus, inbound);
    }

    @Test
    void topicChannelIsGatedByTheTopicAllowlist() {
        Rig rig = rig(List.of(), List.of("greet."));

        rig.hub().receive(frame(EventBridge.Channel.TOPIC, "other.topic"));
        assertTrue(rig.inbound().isEmpty(),
            "a topic outside the allowlist must not reach the local bus");

        rig.hub().receive(frame(EventBridge.Channel.TOPIC, "greet.hello"));
        assertEquals1(rig.inbound(), "an allowlisted topic must be delivered");
    }

    @Test
    void classChannelIsGatedByTheTypeAllowlist() {
        Rig rig = rig(List.of("demo.Greeting"), List.of("greet."));

        // Not on this classpath at all — the allowlist must reject it before
        // any type resolution is attempted.
        rig.hub().receive(frame(EventBridge.Channel.CLASS, "com.evil.Gadget"));
        assertTrue(rig.inbound().isEmpty(),
            "a class outside the allowlist must never be resolved");
    }

    @Test
    void emptyAllowlistIsStillAcceptAllOnBothChannels() {
        // Documented default: an empty list disables the gate. It warns loudly
        // at wire() time instead of silently tightening under an upgrade.
        Rig rig = rig(List.of(), List.of());
        rig.hub().receive(frame(EventBridge.Channel.TOPIC, "other.topic"));
        assertEquals1(rig.inbound(), "empty allowlist = documented accept-all");
    }

    @Test
    void ownOriginIsNeverDispatched() {
        Rig rig = rig(List.of(), List.of());
        CloudEventEnvelope.Parsed looped = new CloudEventEnvelope.Parsed(
            "id-1", "freeway://svc", "greet.hello", null, "inst-1",
            EventBridge.Channel.TOPIC, 1, "\"payload\"");

        rig.hub().receive(looped);

        assertTrue(rig.inbound().isEmpty(), "our own event looped back must be dropped");
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
