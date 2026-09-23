package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.metrics.Metrics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Internal counters for {@link EventBus}: each count feeds both the bus's own
 * {@link EventBus#stats()} snapshot and the container's {@link Metrics} (which
 * may be the no-op default, so it cannot be the snapshot's source).
 */
final class EventStats {

    private final Tally published;
    private final Tally delivered;
    private final Tally subscriberFailures;
    private final Tally deadEvents;
    private final Tally sinkFailures;
    private final Tally streamDrops;

    EventStats(Metrics metrics) {
        this.published = new Tally(metrics.counter("eventbus.published"));
        this.delivered = new Tally(metrics.counter("eventbus.delivered"));
        this.subscriberFailures = new Tally(metrics.counter("eventbus.subscriber_failures"));
        this.deadEvents = new Tally(metrics.counter("eventbus.dead_events"));
        this.sinkFailures = new Tally(metrics.counter("eventbus.sink_failures"));
        this.streamDrops = new Tally(metrics.counter("eventbus.stream_drops"));
    }

    void published() {
        published.increment();
    }

    void delivered() {
        delivered.increment();
    }

    void subscriberFailure() {
        subscriberFailures.increment();
    }

    void deadEvent() {
        deadEvents.increment();
    }

    void sinkFailure() {
        sinkFailures.increment();
    }

    void streamDrop() {
        streamDrops.increment();
    }

    EventBus.EventBusStats snapshot() {
        return new EventBus.EventBusStats(
            published.sum(),
            delivered.sum(),
            subscriberFailures.sum(),
            deadEvents.sum(),
            sinkFailures.sum(),
            streamDrops.sum()
        );
    }

    /** One count, kept locally and mirrored to its metrics counter. */
    private record Tally(LongAdder local, Metrics.Counter metric) {
        Tally(Metrics.Counter metric) {
            this(new LongAdder(), metric);
        }

        void increment() {
            local.increment();
            metric.increment();
        }

        long sum() {
            return local.sum();
        }
    }
}
