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

/** EventBusDispatchTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusDispatchTest {
    @Test
    void moduleSubscribersReceiveEvents() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("got:" + e.post().title())))
        );

        EventBus bus = new EventBus(container);
        bus.publish(new PostCreatedEvent(new Post("hello")));

        assertEquals(1, log.size());
        assertEquals("got:hello", log.get(0));
    }

    @Test
    void multipleModuleSubscribersAllReceive() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("first")));
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("second")));
            }
        );

        new EventBus(container).publish(new PostCreatedEvent(new Post("x")));

        assertEquals(2, log.size());
    }

    @Test
    void stoppableEventShortCircuits() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> {
                        log.add("stop");
                        e.stop();
                    }));
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("never")));
            }
        );

        new EventBus(container).publish(new PostCreatedEvent(new Post("x")));

        assertEquals(1, log.size());
        assertEquals("stop", log.get(0));
    }

    @Test
    void runtimeSubscribersReceiveEvents() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create();

        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> log.add("runtime"));
        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(1, log.size());
        assertEquals("runtime", log.get(0));
    }

    @Test
    void runtimeAndModuleSubscribersBothReceive() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("module")))
        );

        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> log.add("runtime"));
        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(2, log.size());
    }

    @Test
    void unsubscribeRemovesRuntimeSubscriber() {
        List<String> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        Subscription<PostCreatedEvent> sub = bus.subscribe(PostCreatedEvent.class, e -> log.add("x"));
        bus.unsubscribe(sub);

        bus.publish(new PostCreatedEvent(new Post("x")));
        assertTrue(log.isEmpty());
    }

    @Test
    void deadEventFiresWhenNoSubscribers() {
        List<DeadEvent> deads = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(DeadEvent.class, (Consumer<DeadEvent>) deads::add))
        );

        new EventBus(container).publish(new PostCreatedEvent(new Post("orphan")));

        assertEquals(1, deads.size());
        assertInstanceOf(PostCreatedEvent.class, deads.get(0).event());
    }

    @Test
    void deadEventDoesNotSelfLoop() {
        List<DeadEvent> deads = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(DeadEvent.class, (Consumer<DeadEvent>) deads::add))
        );

        new EventBus(container).publish(new PostCreatedEvent(new Post("x")));
        assertEquals(1, deads.size());
    }

    @Test
    void subclassEventReachesSuperclassSubscribers() {
        // A subscriber declared on a parent type receives subtype events.
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(PostCreatedEvent.class, e -> log.add("parent")))
        );
        EventBus bus = new EventBus(container);

        bus.publish(new SpecialPostCreatedEvent(new Post("x")));

        assertEquals(List.of("parent"),
            log, "a subtype event must be delivered to superclass subscribers");
        bus.close();
    }

    @Test
    void superclassEventDoesNotReachSubclassSubscribers() {
        // A subscriber declared on a subtype must NOT receive parent events.
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(SpecialPostCreatedEvent.class, e -> log.add("child")))
        );
        EventBus bus = new EventBus(container);

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(0, log.size(),
            "a parent event must not reach subtype subscribers");
        bus.close();
    }

    @Test
    void superclassSubscriberSuppressesDeadEventForSubclass() {
        // Regression: exact-match dispatch reported DeadEvent for subtype
        // events even when a superclass subscriber existed.
        List<Object> received = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(PostCreatedEvent.class, e -> received.add("event")))
        );
        EventBus bus = new EventBus(container);

        bus.publish(new SpecialPostCreatedEvent(new Post("x")));

        assertEquals(List.of("event"), received,
            "a superclass subscriber must suppress the DeadEvent for a subtype event");
        bus.close();
    }

    @Test
    void subscriberExceptionDoesNotBlockOthers() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> {
                        throw new RuntimeException("boom");
                    }));
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("survive")));
            }
        );

        new EventBus(container).publish(new PostCreatedEvent(new Post("x")));

        assertEquals(1, log.size());
        assertEquals("survive", log.get(0));
    }

    @Test
    void subscriberErrorDoesNotEscapePublish() {
        // Regression: the handler loop caught only Exception, so an Error
        // (AssertionError, OOM, ...) from one subscriber escaped publish() and
        // skipped the remaining subscribers. Errors must be isolated and
        // counted exactly like exceptions.
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> {
                        throw new AssertionError("boom");
                    }));
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("survive")));
            }
        );
        EventBus bus = new EventBus(container);

        assertDoesNotThrow(() -> bus.publish(new PostCreatedEvent(new Post("x"))),
            "a subscriber Error must not escape publish()");

        assertEquals(List.of("survive"), log,
            "the other subscribers must still receive the event after an Error");
        assertEquals(1, bus.stats().subscriberFailures(),
            "the Error must be counted as a subscriber failure");
        bus.close();
    }

    @Test
    void runtimeSubscriberErrorDoesNotEscapePublish() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class).add(
                EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("survive")))
        );
        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> {
            throw new AssertionError("boom");
        });

        assertDoesNotThrow(() -> bus.publish(new PostCreatedEvent(new Post("x"))));
        assertEquals(List.of("survive"), log);
        assertEquals(1, bus.stats().subscriberFailures());
        bus.close();
    }

    @Test
    void topicSubscriberErrorDoesNotEscapePublish() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of("topic", p -> {
                        throw new AssertionError("boom");
                    }));
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of("topic", p -> log.add((String) p)));
            }
        );
        EventBus bus = new EventBus(container);

        assertDoesNotThrow(() -> bus.publish("topic", "payload"),
            "a topic subscriber Error must not escape publish()");
        assertEquals(List.of("payload"), log);
        assertEquals(1, bus.stats().subscriberFailures());
        bus.close();
    }

    @Test
    void subscriberExceptionDoesNotTriggerDeadEventForClassPublish() {
        List<DeadEvent> deads = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(PostCreatedEvent.class, e -> {
                        throw new RuntimeException("boom");
                    }));
                binder.contribute(EventSubscriber.class).add(
                    EventSubscriber.of(DeadEvent.class, (Consumer<DeadEvent>) deads::add));
            }
        );

        new EventBus(container).publish(new PostCreatedEvent(new Post("x")));

        assertTrue(deads.isEmpty());
    }

    @Test
    void closeClearsRuntimeSubscribers() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create();

        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> log.add("x"));
        bus.close();

        // Publishing to a closed bus is a programming error — fail fast.
        assertThrows(IllegalStateException.class,
            () -> bus.publish(new PostCreatedEvent(new Post("x"))));
        assertTrue(log.isEmpty());
    }
}
