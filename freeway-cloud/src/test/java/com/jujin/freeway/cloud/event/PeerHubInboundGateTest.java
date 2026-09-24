package com.jujin.freeway.cloud.event;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbound admission on the cloud plane: the mesh accepts frames from any
 * connected peer that passed the token check, so data gating is structural —
 * the subscription table is the allowlist. A frame whose topic matches no
 * declared subscription is dropped before its payload is read, and no class
 * is ever materialized for a topic nobody declared.
 */
class PeerHubInboundGateTest {

    private static CloudEventEnvelope.Parsed frame(
        CloudEventEnvelope.Channel channel, String type) {
        return new CloudEventEnvelope.Parsed(
            "id-1", "freeway://svc", type, null, "peer-a", channel, "\"payload\"", null, null);
    }

    private record Rig(PeerHub hub, CloudEventBus mesh, List<Object> inbound) {}

    /** Declares "prefix"-subscriptions over a recording String handler. */
    private static Rig rig(String... prefixes) {
        List<CloudEventSubscription> subs = new ArrayList<>();
        List<Object> inbound = new ArrayList<>();
        for (String p : prefixes) {
            subs.add(CloudEventSubscription.of(p, String.class, inbound::add));
        }
        return rigSubs(subs, inbound);
    }

    private static Rig rigSubs(List<CloudEventSubscription> subs, List<Object> inbound) {
        PeerHub hub = new PeerHub();
        CloudEventBus mesh = new CloudEventBus(hub, new JsonCodecDefault(), subs);
        hub.wire(new PeerHub.Wiring(mesh, new JsonCodecDefault(), "svc", "inst-1", ""));
        return new Rig(hub, mesh, inbound);
    }

    /** Counts construction; Jackson instantiates this only when it is asked to. */
    static final class Probed {
        static final AtomicInteger CTOR = new AtomicInteger();
        public Probed() { CTOR.incrementAndGet(); }
    }

    @Test
    void topicFramesAreDeliveredOnlyToDeclaredSubscriptions() {
        Rig rig = rig("greet.");

        rig.hub().receive(frame(CloudEventEnvelope.Channel.TOPIC, "other.topic"));
        assertTrue(rig.inbound().isEmpty(),
            "a topic with no declared subscription must not be delivered");
        assertEquals(1, rig.mesh().stats().droppedNoSubscriber());

        rig.hub().receive(frame(CloudEventEnvelope.Channel.TOPIC, "greet.hello"));
        assertEquals(List.of("payload"), rig.inbound(),
            "the declared prefix receives the deserialized payload");
    }

    @Test
    void undeclaredTypesAreNeverMaterialized() {
        // The stronger half of the gate: dropping must not even deserialize —
        // Probed has a matching JSON body waiting, but its prefix is not
        // declared, so its constructor must never run.
        List<CloudEventSubscription> subs =
            List.of(CloudEventSubscription.of("greet.", Probed.class, p -> {}));
        PeerHub hub = new PeerHub();
        CloudEventBus mesh = new CloudEventBus(hub, new JsonCodecDefault(), subs);
        hub.wire(new PeerHub.Wiring(mesh, new JsonCodecDefault(), "svc", "inst-1", ""));

        hub.receive(new CloudEventEnvelope.Parsed("id-1", "freeway://svc", "other.topic",
            null, "peer-a", CloudEventEnvelope.Channel.TOPIC, "{}", null, null));

        assertEquals(0, Probed.CTOR.get(),
            "no reflective materialization for an undeclared topic");
        assertEquals(1, mesh.stats().droppedNoSubscriber());
    }

    @Test
    void legacyClassFramesAreDroppedByReason() {
        // In-flight frames from pre-teardown nodes route by class name — the
        // plane has no class routing anymore, so they die by channel, before
        // any subscription match and without resolving anything.
        Rig rig = rig("greet.");

        rig.hub().receive(frame(CloudEventEnvelope.Channel.CLASS, "com.evil.Gadget"));

        assertTrue(rig.inbound().isEmpty(),
            "class-channel frames must never be delivered or resolved");
        assertEquals(1, rig.mesh().stats().droppedLegacy(),
            "the rollout window keeps evidence: drops are counted");
    }

    @Test
    void ownOriginIsNeverDispatched() {
        Rig rig = rig("greet.");
        CloudEventEnvelope.Parsed looped = new CloudEventEnvelope.Parsed(
            "id-1", "freeway://svc", "greet.hello", null, "inst-1",
            CloudEventEnvelope.Channel.TOPIC, "\"payload\"", null, null);

        rig.hub().receive(looped);

        assertTrue(rig.inbound().isEmpty(), "our own event looped back must be dropped");
    }

    @Test
    void duplicateSimultaneousDialsKeepSingleConnectionByOriginOrder() {
        PeerHub smallerHub = new PeerHub();
        smallerHub.wire(new PeerHub.Wiring(
            new CloudEventBus(smallerHub, new JsonCodecDefault(), List.of()),
            new JsonCodecDefault(), "svc", "a-node", ""));

        java.util.concurrent.atomic.AtomicBoolean outboundClosed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean inboundClosed = new java.util.concurrent.atomic.AtomicBoolean();
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
        largerHub.wire(new PeerHub.Wiring(
            new CloudEventBus(largerHub, new JsonCodecDefault(), List.of()),
            new JsonCodecDefault(), "svc", "z-node", ""));
        java.util.concurrent.atomic.AtomicBoolean largerOutboundClosed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean largerInboundClosed = new java.util.concurrent.atomic.AtomicBoolean();
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
}
