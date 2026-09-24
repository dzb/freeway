package com.jujin.freeway.ioc.event;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "leaves the JVM" half of {@link EventBus}: sink fan-out, the
 * bus-minted dispatch identity, and inbound deduplication.
 *
 * <p>The bus itself is the in-process broadcast primitive (local subscribers,
 * {@code Defer} buffering, ordered/async channels, streams); everything here
 * exists so events can cross a transport boundary — fanning out to message
 * queues, correlating copies of one event across transports, and dropping
 * redeliveries. Transports arrive as sealed contributions
 * ({@code binder.contribute(EventSink.class)}), never as runtime installs, so
 * this side holds no registry of its own: the contribution store's snapshot
 * is the registry. Dedup capacity arrives the same way, as one contributed
 * {@link EventBridgePolicy}.
 *
 * <p>Package-private: adapters talk to the bus ({@code publishInbound}), never
 * here directly.
 */
final class EventBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EventBridge.class);

    /**
     * The sealed contribution snapshot — stable for the bus's lifetime, released
     * on {@link #clear()} so a closed bus keeps no module channel reachable.
     * Volatile: {@code clear()} runs on the closing thread while dispatches
     * may still be in flight.
     */
    private volatile List<EventSink> sinks;
    private final EventStats stats;
    /** The contributed dedup window; null when no policy (or off) was contributed. */
    private final IdWindow inboundIds;

    EventBridge(EventStats stats, List<EventSink> sinks, List<EventBridgePolicy> policies) {
        this.stats = stats;
        this.sinks = List.copyOf(sinks);
        if (policies.size() > 1) {
            throw new IllegalStateException(
                "Multiple EventBridgePolicy contributions — dedup capacity has one"
                    + " answer per container; contribute exactly one policy");
        }
        int capacity = policies.isEmpty() ? 0 : policies.getFirst().dedupCapacity();
        this.inboundIds = capacity > 0 ? new IdWindow(capacity) : null;
    }

    boolean isEmpty() {
        return sinks.isEmpty();
    }

    /** Releases every sink — bus close must not keep module channels reachable. */
    void clear() {
        sinks = List.of();
    }

    /**
     * Fans one dispatched event out to every contributed sink. Mints the
     * dispatch identity here, once per fan-out, so every sink shares it — and
     * a dispatch that never reaches a sink (no sinks, rolled back, inbound)
     * never pays for a UUID. Inbound dispatches carry their wire id instead
     * (never null here). A throwing sink is isolated and counted; the others
     * still receive it.
     */
    void fanOut(
        String topic,
        Object payload,
        EventSink.Channel channel,
        String eventId,
        Supplier<String> label
    ) {
        String id = eventId != null ? eventId : UUID.randomUUID().toString();
        for (EventSink sink : sinks) {
            try {
                sink.send(topic, payload, channel, id);
            } catch (Exception ex) {
                stats.sinkFailure();
                LOG.warn("Event sink failed for {}", label.get(), ex);
            }
        }
    }

    /** True when {@code eventId} is new to the window (or dedup is off). */
    boolean claim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true; // no identity to correlate on — always deliver
        }
        IdWindow window = inboundIds;
        return window == null || window.claim(eventId);
    }

    /**
     * Insertion-ordered window of the last {@code capacity} inbound ids.
     * Insertion order (not access order) is deliberate: the window answers
     * "have I seen this recently", and re-seeing an id must not extend its
     * life — otherwise a hot id would pin itself in the window forever.
     */
    private static final class IdWindow {
        private final int capacity;
        private final LinkedHashSet<String> seen = new LinkedHashSet<>();

        IdWindow(int capacity) {
            this.capacity = capacity;
        }

        /** @return true if {@code id} was new; false if already present */
        synchronized boolean claim(String id) {
            if (!seen.add(id)) {
                return false;
            }
            if (seen.size() > capacity) {
                Iterator<String> oldest = seen.iterator();
                oldest.next();
                oldest.remove();
            }
            return true;
        }
    }
}
