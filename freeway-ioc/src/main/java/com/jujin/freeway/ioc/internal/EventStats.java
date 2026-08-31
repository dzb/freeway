package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.metrics.Metrics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Internal counters for {@link com.jujin.freeway.ioc.EventBus}.
 */
public final class EventStats {

    private final Metrics.Counter cPublished;
    private final Metrics.Counter cDelivered;
    private final Metrics.Counter cSubscriberFailures;
    private final Metrics.Counter cDeadEvents;
    private final LongAdder published = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder subscriberFailures = new LongAdder();
    private final LongAdder deadEvents = new LongAdder();

    public EventStats(Metrics metrics) {
        this.cPublished = metrics.counter("eventbus.published");
        this.cDelivered = metrics.counter("eventbus.delivered");
        this.cSubscriberFailures = metrics.counter("eventbus.subscriber_failures");
        this.cDeadEvents = metrics.counter("eventbus.dead_events");
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
}
