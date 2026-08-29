package com.jujin.freeway.cloud.events;

import com.jujin.freeway.ioc.EventBridge;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbound hook for the CloudEventBus: translates {@code publish}ed events
 * into CloudEvents 1.0 frames and fans them out to live peer connections,
 * filtered by each peer's declared subscription prefixes.
 *
 * <p>Implements {@link EventBridge} — installed via
 * {@code EventBus.addEventBridge} by {@link CloudEventModule}'s hook when
 * enabled. Semantics: at-most-once, best-effort; a failed send is logged and
 * the connection is dropped (reconnect is the connector's job). Events
 * short-circuited by {@code Stoppable} never leave the JVM. This class never
 * blocks the publishing thread beyond a socket write.</p>
 */
public final class CloudEventBridge implements EventBridge {

    private static final Logger LOG = LoggerFactory.getLogger(CloudEventBridge.class);

    private final PeerHub hub;

    public CloudEventBridge(PeerHub hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @Override
    public void send(String topic, Object event) {
        send(topic, event, EventBridge.Channel.CLASS);
    }

    @Override
    public void send(String topic, Object event, EventBridge.Channel channel) {
        if (event instanceof com.jujin.freeway.ioc.EventBus.Stoppable s && s.isStopped()) {
            return; // short-circuited locally — a vetoed fact does not broadcast
        }
        String origin = hub.origin();
        if (origin == null) {
            return; // not wired — inert
        }
        String json;
        try {
            json = CloudEventEnvelope.translate(
                event, topic, channel, origin, hub.serviceId(), hub.codec());
        } catch (RuntimeException e) {
            LOG.error("Event translation failed — not bridged (topic={})", topic, e);
            return;
        }

        int sent = 0;
        for (PeerConnection peer : hub.connections()) {
            // Self-connection guard: an origin equal to ours would be a
            // loop (we dialed ourselves, or a duplicated instanceId). Peers
            // with a shared instanceId config are thus skipped — prefer
            // per-node instance ids.
            if (peer.remoteOrigin().equals(origin)) {
                continue;
            }
            String type = channel == EventBridge.Channel.CLASS
                ? event.getClass().getName()
                : null;
            if (!peer.matches(type, topic)) {
                continue; // peer did not declare interest in this type/topic
            }
            if (peer.send(json)) {
                sent++;
            } else {
                LOG.warn("Send to peer {} failed — dropping connection", peer.remoteOrigin());
                hub.unregister(peer.remoteOrigin());
            }
        }
        if (sent == 0) {
            LOG.debug("No peers interested in '{}' — not bridged", topic);
        }
    }
}
