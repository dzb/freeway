package com.jujin.freeway.ioc;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.scoped.Defer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


    // ==================== event types ====================

/** EventBusStatsTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusStatsTest {
    @Test
    void statsCountPublishDeliveriesAndFailures() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("ok")))
        );
        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> {
            throw new IllegalStateException("boom");
        });

        bus.publish(new PostCreatedEvent(new Post("x")));
        bus.publish(new CommentAddedEvent(1L, "y")); // zero subscribers -> DeadEvent

        EventBus.EventBusStats stats = bus.stats();
        assertEquals(2, stats.published(), "one dispatch per publish");
        assertEquals(1, stats.delivered(), "only the healthy subscriber delivery");
        assertEquals(1, stats.subscriberFailures(), "the throwing runtime subscriber");
        assertEquals(1, stats.deadEvents(), "zero-subscriber event emits a DeadEvent");
        bus.close();
    }

    @Test
    void metricsCountersMirrorDispatchStats() {
        // The container's Metrics builtin (NoopMetrics) must be observable
        // through a contributed implementation — counters mirror stats().
        var counters = new ConcurrentHashMap<String, Metrics.Counter>();
        Metrics metrics = new Metrics() {
            @Override public Counter counter(String name) {
                return counters.computeIfAbsent(name, n -> new Counter() {
                    final LongAdder adder =
                        new LongAdder();
                    @Override public void increment() { adder.increment(); }
                    @Override public void add(long delta) { adder.add(delta); }
                    @Override public long value() { return adder.sum(); }
                });
            }
            @Override public void gauge(String name, Supplier<Number> v) { }
        };
        Container container = Freeway.create(binder ->
            binder.bind(Metrics.class).to(c -> metrics).primary());
        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> { });

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(1, counters.get("eventbus.published").value());
        assertEquals(1, counters.get("eventbus.delivered").value());
        bus.close();
    }

    @Test
    void throwingSinkIsIsolatedAndCounted() {
        // A failing sink must not abort the fan-out (other sinks still get
        // the event) and must surface in stats(), not only in a log line.
        List<Object> delivered = new ArrayList<>();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) -> {
                    throw new IllegalStateException("boom");
                });
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) -> delivered.add(event));
        });
        EventBus bus = container.get(EventBus.class);

        bus.publish("orders", "payload"); // topic channel

        EventBus.EventBusStats stats = bus.stats();
        assertEquals(1, stats.sinkFailures(), "the throwing sink is counted");
        assertEquals(List.of("payload"), delivered,
            "the healthy sink still receives the event after its sibling failed");
        assertEquals(0, stats.streamDrops(), "no stream involved");
        container.close();
    }

    @Test
    void deadEventSourceIsThePublishingBus() {
        // New contract pin: DeadEvent.source identifies the bus that emitted
        // the diagnostic (it used to leak the internal EventDispatcher).
        Container container = Freeway.create(binder -> { });
        EventBus bus = new EventBus(container);
        List<DeadEvent> dead = new ArrayList<>();
        bus.subscribe(DeadEvent.class, dead::add);

        bus.publish("nobody-listens");

        assertEquals(1, dead.size());
        assertEquals(bus, dead.get(0).source(),
            "DeadEvent.source must be the EventBus, not an internal component");
        bus.close();
    }
}
