package com.jujin.freeway.cloud.event;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.scoped.Defer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cloud-native broadcast plane: publish and receive CloudEvents 1.0
 * frames over the WS mesh — explicitly, at the call site.
 *
 * <p><b>Publish leaves the JVM and nothing else.</b> This bus owns its
 * subscription table; local facts still travel on the in-process
 * {@code com.jujin.freeway.ioc.event.EventBus}, and a mesh fact becomes a
 * local one only where an application mirrors it on purpose (a
 * {@link CloudEventSubscription} whose handler calls the local bus). Nothing
 * here ever publishes there by itself, and nothing on the local bus ever
 * arrives here by module loading: the two planes are separate decisions.</p>
 *
 * <p><b>Delivery semantics:</b> at-most-once, best-effort volatile fabric.
 * A failed send is logged, counted and the connection dropped (reconnect is
 * the connector's job); the frame is not retried and not queued. A throwing
 * handler is isolated from the others. Publishing inside a {@code Defer}
 * scope (e.g. a DB transaction) buffers the publish until the scope
 * commits — a rollback discards it, so an uncommitted fact never leaves the
 * JVM. Ordering across the mesh is not promised; per-key ordering is a
 * durable-stream concern, which is what an MQ plane (Kafka) is for.</p>
 *
 * <p><b>Subscriptions are composition-time declarations</b>
 * ({@code contribute(CloudEventSubscription.class)}), and they are the
 * mesh's single source of interest: the declared prefixes are what the
 * hello handshake tells peers to pull, what the inbound gate matches frames
 * against, and where delivery routes. There is no runtime subscribe —
 * interest a peer cannot know about at handshake is interest that cannot
 * be honored.</p>
 *
 * <p>Inbound frames are delivered once each: the mesh is at-most-once, so
 * there are no redeliveries to deduplicate, and one event has no second
 * transport to copy it across planes. Legacy {@code class}-channel frames
 * still in flight from pre-teardown nodes decode but are dropped —
 * counted and logged, never deserialized.</p>
 *
 * <p>Built by {@link CloudEventModule}; inert until the mesh is wired (see
 * {@link CloudEventLifecycleHook}): with no peers configured and the mesh
 * off, publish is a debug-logged no-op.</p>
 */
public final class CloudEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(CloudEventBus.class);

    private final PeerHub hub;
    private final JsonCodec codec;
    /** Sealed at composition: the contribution store's snapshot is the table. */
    private final List<CloudEventSubscription> subscriptions;

    // Each plane keeps its own books (no cross-plane merged view).
    private final LongAdder published = new LongAdder();
    private final LongAdder framesSent = new LongAdder();
    private final LongAdder sendFailures = new LongAdder();
    private final LongAdder inboundDelivered = new LongAdder();
    private final LongAdder droppedLegacy = new LongAdder();
    private final LongAdder droppedNoSubscriber = new LongAdder();

    CloudEventBus(PeerHub hub, JsonCodec codec, List<CloudEventSubscription> subscriptions) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.subscriptions = List.copyOf(subscriptions);
    }

    // ==================== publish ====================

    /**
     * Publish a payload on a mesh topic. The topic string is the CloudEvents
     * {@code type} on the wire — routing keys on this plane are names, never
     * Java class names.
     *
     * <p>The payload may be {@code null} (signal semantics — the topic
     * carries the meaning). Inside a {@code Defer} scope the publish is
     * buffered and sent only after the scope commits; a rollback discards
     * it.</p>
     */
    public void publish(String topic, Object payload) {
        publish(topic, payload, null);
    }

    /**
     * Publish with an optional {@code subject} — the CE partition/ordering
     * hint, carried on the wire and left to consumers (the mesh itself
     * imposes no ordering). Null/blank omits the attribute.
     */
    public void publish(String topic, Object payload, String subject) {
        Objects.requireNonNull(topic, "topic");
        // The commit gate must be evaluated on THIS thread (the same Defer
        // discipline as the local bus): an executor or later thread would
        // not see the active scope.
        if (Defer.isActive()) {
            Defer.defer(() -> doPublish(topic, payload, subject));
        } else {
            doPublish(topic, payload, subject);
        }
    }

    private void doPublish(String topic, Object payload, String subject) {
        published.increment();
        String origin = hub.origin();
        if (origin == null) {
            LOG.debug("Cloud event published to an unwired mesh — dropped (topic={})", topic);
            return;
        }
        // Filter first, translate second: with no listening peer the payload
        // never pays for serialization (the bridge era serialized before
        // asking, taxing every publish on any node's curiosity).
        List<PeerConnection> targets = new ArrayList<>();
        for (PeerConnection peer : hub.connections()) {
            // Self-connection guard: an origin equal to ours would be a loop
            // (we dialed ourselves, or a duplicated instanceId).
            if (EventOrigin.isOwn(origin, peer.remoteOrigin())) {
                continue;
            }
            if (peer.matchesTopic(topic)) {
                targets.add(peer);
            }
        }
        if (targets.isEmpty()) {
            LOG.debug("No peers interested in '{}' — not sent", topic);
            return;
        }
        String json;
        try {
            json = CloudEventEnvelope.translate(
                topic, payload, subject, origin, hub.serviceId(), codec,
                UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            LOG.error("Cloud event translation failed — not sent (topic={})", topic, e);
            return;
        }
        for (PeerConnection peer : targets) {
            if (peer.send(json)) {
                framesSent.increment();
            } else {
                sendFailures.increment();
                LOG.warn("Send to peer {} failed — dropping connection", peer.remoteOrigin());
                hub.unregister(peer);
                // Unregistering only detaches the route; the socket stays
                // open and keeps pushing inbound events we no longer trust
                // with outbound traffic. Close it (idempotent) so the
                // connection state converges.
                peer.close();
            }
        }
    }

    // ==================== inbound ====================

    /**
     * Deliver one admitted frame to the matching subscriptions. The peer
     * handshake and interceptor chain ran before this; a frame arriving here
     * is authenticated and un-duplicated by construction (at-most-once).
     */
    void deliver(CloudEventEnvelope.Parsed frame) {
        if (frame.channel() == CloudEventEnvelope.Channel.CLASS) {
            // Legacy frames die with the bridge: the wire gives them no topic
            // to route on and this plane's table is topic-prefixed. Dropped by
            // reason — counted and logged, never deserialized (no reflective
            // class loading for a class nobody may subscribe to).
            droppedLegacy.increment();
            LOG.debug("Legacy class-channel frame dropped (topic-routing plane): {}", frame.type());
            return;
        }
        List<CloudEventSubscription> hits = matching(frame.type());
        if (hits.isEmpty()) {
            // The subscription table is the inbound allowlist: an undeclared
            // topic is dropped before its payload is read, and no class is
            // materialized for it.
            droppedNoSubscriber.increment();
            LOG.debug("No subscription for mesh topic '{}' — dropped", frame.type());
            return;
        }
        // The inbound trace, restored around delivery so handlers observe the
        // sender's causality. Absent/unparseable runs bare — a traceless frame
        // must not clear the consuming thread's ambient.
        Map<String, String> trace = new LinkedHashMap<>();
        if (frame.traceparent() != null) trace.put(EventTrace.TRACEPARENT, frame.traceparent());
        if (frame.tracestate() != null) trace.put(EventTrace.TRACESTATE, frame.tracestate());
        String dataJson = frame.dataJson();
        boolean signal = dataJson == null || "null".equals(dataJson);
        for (CloudEventSubscription sub : hits) {
            try {
                Object payload = signal ? null : codec.fromJson(dataJson, sub.type());
                EventTrace.runWithTrace(trace, () -> sub.handler().accept(payload));
                inboundDelivered.increment();
            } catch (RuntimeException e) {
                // Handler failures are isolated (the others still receive);
                // a payload that will not decode into the declared type is
                // this node's configuration fault — log it loudly.
                LOG.error("Cloud event delivery failed for subscription '{}' (topic={})",
                    sub.prefix(), frame.type(), e);
            }
        }
    }

    private List<CloudEventSubscription> matching(String topic) {
        List<CloudEventSubscription> hits = new ArrayList<>();
        for (CloudEventSubscription sub : subscriptions) {
            if (sub.prefix().isEmpty() || topic.startsWith(sub.prefix())) {
                hits.add(sub);
            }
        }
        return hits;
    }

    // ==================== mesh views ====================

    /**
     * Declared topic prefixes — the hello handshakes (both legs) carry these,
     * so outbound interest on peers and inbound gating here share one source.
     */
    List<String> prefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (CloudEventSubscription sub : subscriptions) {
            prefixes.add(sub.prefix());
        }
        return List.copyOf(prefixes);
    }

    /** True when this node declared any inbound interest (outbound-only otherwise). */
    boolean hasSubscriptions() {
        return !subscriptions.isEmpty();
    }

    // ==================== stats ====================

    /**
     * Immutable snapshot of this plane's counters.
     *
     * @param published            publish calls flushed (post-commit or immediate)
     * @param framesSent           frames written to at least one peer connection
     * @param sendFailures         failed sends (isolated, connection dropped, frame lost)
     * @param inboundDelivered     successful handler invocations (one per matching subscription)
     * @param droppedLegacy        in-flight CLASS-channel frames dropped by reason (rollout observability)
     * @param droppedNoSubscriber  topic frames matching no subscription (normal gating)
     */
    public record CloudEventStats(
        long published,
        long framesSent,
        long sendFailures,
        long inboundDelivered,
        long droppedLegacy,
        long droppedNoSubscriber
    ) {}

    public CloudEventStats stats() {
        return new CloudEventStats(
            published.sum(), framesSent.sum(), sendFailures.sum(),
            inboundDelivered.sum(), droppedLegacy.sum(), droppedNoSubscriber.sum());
    }
}
