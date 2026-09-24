package com.jujin.freeway.cloud.event;

import com.jujin.freeway.ioc.EventSink;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbound hook for the CloudEventBus: translates {@code publish}ed event
 * into CloudEvents 1.0 frames and fans them out to live peer connections,
 * filtered by each peer's declared subscription prefixes.
 *
 * <p>Implements {@link EventSink} — contributed by {@link CloudEventModule}
 * (sealed at composition, no runtime install). Semantics: at-most-once,
 * best-effort; a failed send is logged and
 * the connection is dropped (reconnect is the connector's job). Events
 * short-circuited by {@code Stoppable} never leave the JVM. This class never
 * blocks the publishing thread beyond a socket write.</p>
 */
final class CloudEventSink implements EventSink {

    private static final Logger LOG = LoggerFactory.getLogger(CloudEventSink.class);

    private final PeerHub hub;

    public CloudEventSink(PeerHub hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @Override
    public void send(String topic, Object event, EventSink.Channel channel, String eventId) {
        if (event instanceof com.jujin.freeway.ioc.EventBus.Stoppable s && s.isStopped()) {
            return; // short-circuited locally — a vetoed fact does not broadcast
        }
        String origin = hub.origin();
        if (origin == null) {
            return; // not wired — inert
        }
        String json;
        try {
            // The bus-minted id, not a fresh one: a peer that receives this
            // same event over a second transport has to be able to tell it is
            // the same event, or the duplicate is unrecognizable.
            json = CloudEventEnvelope.translate(
                event, topic, channel, origin, hub.serviceId(), hub.codec(), eventId);
        } catch (RuntimeException e) {
            LOG.error("Event translation failed — not sent (topic={})", topic, e);
            return;
        }

        int sent = 0;
        for (PeerConnection peer : hub.connections()) {
            // Self-connection guard: an origin equal to ours would be a
            // loop (we dialed ourselves, or a duplicated instanceId). Peers
            // with a shared instanceId config are thus skipped — prefer
            // per-node instance ids.
            if (EventOrigin.isOwn(origin, peer.remoteOrigin())) {
                continue;
            }
            String type = channel == EventSink.Channel.CLASS
                ? event.getClass().getName()
                : null;
            if (!peer.matches(type, topic)) {
                continue; // peer did not declare interest in this type/topic
            }
            if (peer.send(json)) {
                sent++;
            } else {
                LOG.warn("Send to peer {} failed — dropping connection", peer.remoteOrigin());
                hub.unregister(peer);
                // Unregistering only detaches the route; the socket stays
                // open and keeps pushing inbound event we no longer trust
                // with outbound traffic. Close it (idempotent) so the
                // connection state converges.
                peer.close();
            }
        }
        if (sent == 0) {
            LOG.debug("No peers interested in '{}' — not sent", topic);
        }
    }
}
