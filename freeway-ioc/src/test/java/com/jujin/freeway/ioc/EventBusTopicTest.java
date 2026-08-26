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

/** EventBusTopicTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusTopicTest {
    @Test
    void stringTopicModuleSubscriberReceivesPayload() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of("post.created", p -> log.add((String) p)))
        );

        EventBus bus = new EventBus(container);
        bus.publish("post.created", "hello");

        assertEquals(1, log.size());
        assertEquals("hello", log.get(0));
    }

    @Test
    void stringTopicRuntimeSubscriberReceivesPayload() {
        List<String> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe("post.created", p -> log.add((String) p));
        bus.publish("post.created", "runtime");

        assertEquals(1, log.size());
        assertEquals("runtime", log.get(0));
    }

    @Test
    void differentTopicsDontCrossFire() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of("post.created", p -> log.add("post:" + p)))
        );

        EventBus bus = new EventBus(container);
        bus.publish("comment.added", "test");

        // "comment.added" has no subscriber → DeadEvent
        assertTrue(log.isEmpty());
    }

    @Test
    void singleArgStringPublishIsClassEventNotTopic() {
        // Contract fix: publish("x") dispatches a String CLASS event — topic
        // subscribers on "x" must NOT receive it, String.class subscribers must.
        List<String> topicLog = new ArrayList<>();
        List<String> classLog = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of("x", p -> topicLog.add((String) p)));
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of(String.class, p -> classLog.add(p)));
            }
        );
        EventBus bus = new EventBus(container);

        bus.publish("x");

        assertTrue(topicLog.isEmpty(),
            "a single-argument publish(String) must not reach topic subscribers");
        assertEquals(List.of("x"), classLog,
            "a single-argument publish(String) must dispatch as a String class event");
        bus.close();
    }

    @Test
    void topicPublishDoesNotReachStringClassSubscribers() {
        // Mirror image: publish("x", payload) is topic delivery — a
        // String.class subscriber must not receive the topic payload.
        List<String> classLog = new ArrayList<>();
        List<String> topicLog = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of(String.class, p -> classLog.add(p)));
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of("x", p -> topicLog.add((String) p)));
            }
        );
        EventBus bus = new EventBus(container);

        bus.publish("x", "payload");

        assertEquals(List.of("payload"), topicLog, "the topic subscriber must receive the payload");
        assertTrue(classLog.isEmpty(),
            "a two-argument topic publish must not reach String.class subscribers");
        bus.close();
    }

    @Test
    void stringTopicDeadEventForZeroSubscribers() {
        List<DeadEvent> deads = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(DeadEvent.class, (Consumer<DeadEvent>) deads::add))
        );

        new EventBus(container).publish("no.such.topic", "orphan");

        assertEquals(1, deads.size());
        assertEquals("orphan", deads.get(0).event());
    }

    @Test
    void stringTopicUnsubscribe() {
        List<String> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        Subscription<Object> sub = bus.subscribe("topic", p -> log.add((String) p));
        bus.unsubscribe(sub);
        bus.publish("topic", "x");

        assertTrue(log.isEmpty());
    }

    @Test
    void subscriberExceptionDoesNotTriggerDeadEventForTopicPublish() {
        List<DeadEvent> deads = new ArrayList<>();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of("topic", p -> {
                        throw new RuntimeException("boom");
                    }));
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of(DeadEvent.class, (Consumer<DeadEvent>) deads::add));
            }
        );

        new EventBus(container).publish("topic", "payload");

        assertTrue(deads.isEmpty());
    }
}
