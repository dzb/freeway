package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.metrics.Metrics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Internal counters for {@link com.jujin.freeway.ioc.EventBus}.
 */
final class EventStats {

    private final Metrics.Counter cPublished;
    private final Metrics.Counter cDelivered;
    private final Metrics.Counter cSubscriberFailures;
    private final Metrics.Counter cDeadEvents;
    private final Metrics.Counter cSinkFailures;
    private final Metrics.Counter cStreamDrops;
    private final LongAdder published = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder subscriberFailures = new LongAdder();
    private final LongAdder deadEvents = new LongAdder();
    private final LongAdder sinkFailures = new LongAdder();
    private final LongAdder streamDrops = new LongAdder();

    public EventStats(Metrics metrics) {
        this.cPublished = metrics.counter("eventbus.published");
        this.cDelivered = metrics.counter("eventbus.delivered");
        this.cSubscriberFailures = metrics.counter("eventbus.subscriber_failures");
        this.cDeadEvents = metrics.counter("eventbus.dead_events");
        this.cSinkFailures = metrics.counter("eventbus.sink_failures");
        this.cStreamDrops = metrics.counter("eventbus.stream_drops");
    }

    public void published() {
        published.increment();
        cPublished.increment();
    }

    public void delivered() {
        delivered.increment();
        cDelivered.increment();
    }

    public void subscriberFailure() {
        subscriberFailures.increment();
        cSubscriberFailures.increment();
    }

    public void deadEvent() {
        deadEvents.increment();
        cDeadEvents.increment();
    }

    public void sinkFailure() {
        sinkFailures.increment();
        cSinkFailures.increment();
    }

    public void streamDrop() {
        streamDrops.increment();
        cStreamDrops.increment();
    }

    public long publishedCount() {
        return published.sum();
    }

    public long deliveredCount() {
        return delivered.sum();
    }

    public long subscriberFailureCount() {
        return subscriberFailures.sum();
    }

    public long deadEventCount() {
        return deadEvents.sum();
    }

    public long sinkFailureCount() {
        return sinkFailures.sum();
    }

    public long streamDropCount() {
        return streamDrops.sum();
    }
}
