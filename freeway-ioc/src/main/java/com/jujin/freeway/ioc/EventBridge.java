package com.jujin.freeway.ioc;

import java.util.Iterator;
import java.util.LinkedHashSet;
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
 * redeliveries. That half grew up serving the cloud event mesh, and it reads
 * as one unit so a future move (next to its only consumer) is a file move,
 * not an excavation.
 *
 * <p>Package-private: adapters talk to the bus ({@code addEventSink},
 * {@code publishInbound}, {@code inboundDeduplication}), never here directly.
 */
final class EventBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EventBridge.class);

    private final EventSinkRegistry sinks = new EventSinkRegistry();
    private final EventStats stats;
    /** Bounded window of recent inbound wire ids; null when dedup is off. */
    private volatile IdWindow inboundIds;

    EventBridge(EventStats stats) {
        this.stats = stats;
    }

    void add(EventSink sink) {
        sinks.add(sink);
    }

    boolean remove(EventSink sink) {
        return sinks.remove(sink);
    }

    /** Detaches every sink — bus close must not keep module channels reachable. */
    void clear() {
        sinks.clear();
    }

    boolean isEmpty() {
        return sinks.isEmpty();
    }

    /**
     * Fans one dispatched event out to every sink. Mints the dispatch identity
     * here, once per fan-out, so every sink shares it — and a dispatch that
     * never reaches a sink (no sinks, rolled back, inbound) never pays for a
     * UUID. Inbound dispatches carry their wire id instead (never null here).
     * A throwing sink is isolated and counted; the others still receive it.
     */
    void fanOut(
        String topic,
        Object payload,
        EventSink.Channel channel,
        String eventId,
        Supplier<String> label
    ) {
        if (sinks.isEmpty()) {
            return; // re-check: remove/clear may have raced the caller's guard
        }
        String id = eventId != null ? eventId : UUID.randomUUID().toString();
        for (EventSink sink : sinks.snapshot()) {
            try {
                sink.send(topic, payload, channel, id);
            } catch (Exception ex) {
                stats.sinkFailure();
                LOG.warn("Event sink failed for {}", label.get(), ex);
            }
        }
    }

    synchronized void deduplication(int capacity) {
        if (capacity <= 0) {
            inboundIds = null;
        } else if (inboundIds == null || inboundIds.capacity() != capacity) {
            inboundIds = new IdWindow(capacity);
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

        int capacity() {
            return capacity;
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
