package com.jujin.freeway.ioc.event;

import com.jujin.freeway.commons.metrics.Metrics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Internal counters for {@link EventBus}: each count feeds both the bus's own
 * {@link EventBus#stats()} snapshot and the container's {@link Metrics} (which
 * may be the no-op default, so it cannot be the snapshot's source).
 */
final class EventStats {

    private final CounterState published;
    private final CounterState delivered;
    private final CounterState subscriberFailures;
    private final CounterState deadEvents;
    private final CounterState streamDrops;

    EventStats(Metrics metrics) {
        this.published = new CounterState(metrics.counter("eventbus.published"));
        this.delivered = new CounterState(metrics.counter("eventbus.delivered"));
        this.subscriberFailures = new CounterState(metrics.counter("eventbus.subscriber.failures"));
        this.deadEvents = new CounterState(metrics.counter("eventbus.dead.events"));
        this.streamDrops = new CounterState(metrics.counter("eventbus.stream.drops"));
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

    void streamDrop() {
        streamDrops.increment();
    }

    EventBus.Stats snapshot() {
        return new EventBus.Stats(
            published.sum(),
            delivered.sum(),
            subscriberFailures.sum(),
            deadEvents.sum(),
            streamDrops.sum()
        );
    }

    /** One count, kept locally and mirrored to its metrics counter. */
    private record CounterState(LongAdder local, Metrics.Counter metric) {
        CounterState(Metrics.Counter metric) {
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
