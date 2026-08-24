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

class EventBusTest {

    // ==================== event types ====================

    static class PostCreatedEvent implements EventBus.Stoppable {
        private final Post post;
        private final AtomicBoolean stopped = new AtomicBoolean();
        PostCreatedEvent(Post post) { this.post = post; }
        Post post() { return post; }
        @Override public void stop() { stopped.set(true); }
        @Override public boolean isStopped() { return stopped.get(); }
    }

    /** Subtype used to verify hierarchy dispatch. */
    static class SpecialPostCreatedEvent extends PostCreatedEvent {
        SpecialPostCreatedEvent(Post post) { super(post); }
    }

    record Post(String title) {}

    record CommentAddedEvent(Long postId, String text) {}

    // ==================== contribute-based subscribers ====================

    @Test
    void moduleSubscribersReceiveEvents() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, (Consumer<PostCreatedEvent>) e -> log.add("got:" + e.post().title)))
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

    // ==================== string-topic subscribers ====================

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

    // ==================== async ====================

    @Test
    void asyncPublishDeliversToSubscriber() throws Exception {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("async")))
        );

        EventBus bus = new EventBus(container);
        bus.publishAsync(new PostCreatedEvent(new Post("x")));
        Thread.sleep(200); // wait for async thread

        assertEquals(1, log.size());
        assertEquals("async", log.get(0));
    }

    @Test
    void asyncStringTopicPublishDelivers() throws Exception {
        List<String> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe("topic", p -> log.add((String) p));
        bus.publishAsync("topic", "hello");
        Thread.sleep(200);

        assertEquals(1, log.size());
    }

    @Test
    void syncPublishStillWorksAfterAsyncConfig() throws Exception {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("sync")))
        );

        EventBus bus = new EventBus(container);
        bus.setAsyncExecutor(Executors.newVirtualThreadPerTaskExecutor());
        bus.publish(new PostCreatedEvent(new Post("x"))); // sync, not async

        assertEquals(1, log.size());
    }

    @Test
    void publishAsyncInsideDeferScopeDefersUntilScopeEnds() throws Exception {
        // Regression: publishAsync submitted to the executor directly — the
        // executor thread does NOT inherit the Defer ScopedValue binding, so
        // the guard inside publish() saw no scope and dispatched before the
        // scope ended (and on rollback). The submission itself must defer.
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("event")))
        );
        EventBus bus = new EventBus(container);

        Defer.within(() -> {
            bus.publishAsync(new PostCreatedEvent(new Post("x")));
            assertTrue(log.isEmpty(),
                "async publish inside a Defer scope must not deliver before the scope ends");

        });
        Await.until(2000, () -> log.size() == 1);

        assertEquals(List.of("event"), log,
            "the deferred async event must be delivered after the scope commits");
        bus.close();
    }

    @Test
    void bridgeFailureDoesNotEscapePublish() {
        // Regression: bridge.send sat outside any try/catch, so a failing
        // bridge escaped publish() to the caller in the immediate path while
        // the Defer path only warn-logged it — asymmetric behavior.
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> { }))
        );
        EventBus bus = new EventBus(container);
        bus.setEventBridge((topic, event) -> {
            throw new IllegalStateException("mq down");
        });

        assertDoesNotThrow(() -> bus.publish(new PostCreatedEvent(new Post("x"))),
            "a failing bridge must be isolated like a failing subscriber");
        bus.close();
    }

    @Test
    void stoppedEventIsNotBridged() {
        // A Stoppable event short-circuited by its subscribers must not leave
        // the process via the bridge.
        List<String> bridged = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> e.stop()))
        );
        EventBus bus = new EventBus(container);
        bus.setEventBridge((topic, event) -> bridged.add(event.getClass().getSimpleName()));

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(0, bridged.size(),
            "a stopped event must not reach the bridge");
        bus.close();
    }

    @Test
    void inboundClassEventIsDeliveredLocallyButNotBridged() {
        // publishInbound must reach local class subscribers yet never echo
        // back through the bridge — re-bridging inbound traffic would loop
        // the event around the MQ indefinitely.
        List<PostCreatedEvent> received = new ArrayList<>();
        List<String> bridged = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, received::add))
        );
        EventBus bus = new EventBus(container);
        bus.setEventBridge((topic, event) -> bridged.add(event.getClass().getSimpleName()));

        bus.publishInbound(new PostCreatedEvent(new Post("remote")));

        assertEquals(List.of("remote"),
            received.stream().map(e -> e.post().title()).toList(),
            "inbound event must be delivered to local class subscribers");
        assertEquals(0, bridged.size(),
            "inbound events must never be re-bridged to the MQ");
        bus.close();
    }

    @Test
    void inboundTopicEventIsDeliveredLocallyButNotBridged() {
        List<String> received = new ArrayList<>();
        List<String> bridged = new ArrayList<>();
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        bus.subscribe("order.placed", payload -> received.add(String.valueOf(payload)));
        bus.setEventBridge((topic, event) -> bridged.add(topic));

        bus.publishInbound("order.placed", "from-remote");

        assertEquals(List.of("from-remote"), received,
            "inbound topic event must be delivered to local topic subscribers");
        assertEquals(0, bridged.size(),
            "inbound topic events must never be re-bridged to the MQ");
        bus.close();
    }

    @Test
    void bridgeReceivesDispatchChannel() {
        // The bridge must learn whether an event was published on the
        // class channel or the topic channel so adapters can stamp the
        // wire envelope accordingly (inbound dispatch must mirror it).
        List<EventBridge.Channel> channels = new ArrayList<>();
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        bus.setEventBridge(new EventBridge() {
            @Override
            public void send(String topic, Object event) {
                channels.add(null);
            }

            @Override
            public void send(String topic, Object event, EventBridge.Channel channel) {
                channels.add(channel);
            }
        });

        bus.publish(new PostCreatedEvent(new Post("x")));
        bus.publish("order.placed", "payload");

        assertEquals(
            List.of(EventBridge.Channel.CLASS, EventBridge.Channel.TOPIC),
            channels,
            "class events must bridge as CLASS, topic events as TOPIC");
        bus.close();
    }

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
            binder.bind(Metrics.class).to(metrics).primary());
        EventBus bus = new EventBus(container);
        bus.subscribe(PostCreatedEvent.class, e -> { });

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(1, counters.get("eventbus.published").value());
        assertEquals(1, counters.get("eventbus.delivered").value());
        bus.close();
    }

    @Test
    void publishOrderedDispatchesInSubmissionOrder() throws Exception {
        List<Integer> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe(Integer.class, log::add);

        for (int i = 0; i < 50; i++) {
            bus.publishOrdered("key", i);
        }
        Await.until(5000, () -> log.size() == 50);
        for (int i = 0; i < 50; i++) {
            assertEquals(Integer.valueOf(i), log.get(i),
                "ordered events must dispatch in submission order");
        }
        bus.close();
    }

    @Test
    void publishOrderedInsideDeferScopeKeepsOrder() throws Exception {
        List<Integer> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe(Integer.class, log::add);

        Defer.within(() -> {
            bus.publishOrdered("key", 1);
            bus.publishOrdered("key", 2);
            bus.publishOrdered("key", 3);
            assertEquals(0, log.size(),
                "ordered events inside a Defer scope must wait for the scope end");
        });
        Await.until(2000, () -> log.size() == 3);

        assertEquals(List.of(1, 2, 3), log,
            "ordered events must drain in call order after the scope commits");
        bus.close();
    }


    // ==================== Defer integration ====================

    @Test
    void publishInsideDeferScopeIsDeferredUntilCommit() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("event")))
        );
        EventBus bus = new EventBus(container);

        Defer.within(() -> {
            bus.publish(new PostCreatedEvent(new Post("x")));
            assertTrue(log.isEmpty(), "event should be buffered, not published immediately");
        });

        assertEquals(List.of("event"), log);
    }

    @Test
    void publishInsideDeferScopeDiscardsOnRollback() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("event")))
        );
        EventBus bus = new EventBus(container);

        assertThrows(RuntimeException.class, () -> {
            Defer.within(() -> {
                bus.publish(new PostCreatedEvent(new Post("x")));
                throw new RuntimeException("rollback");
            });
        });

        assertTrue(log.isEmpty(), "deferred event should be discarded on rollback");
    }

    @Test
    void deferredEventsDrainingAfterCloseAreSilentNoOps() {
        // Regression: events buffered before close() drained after close,
        // delivering to module subscribers only (runtime lists were cleared)
        // — a partial delivery of an accepted event.
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("event")))
        );
        EventBus bus = new EventBus(container);

        Defer.within(() -> {
            bus.publish(new PostCreatedEvent(new Post("x")));
            bus.close();

        });

        assertEquals(0, log.size(),
            "an event draining after close must not be partially delivered to module subscribers only");
    }

    @Test
    void zeroSubscriberDeferredEventAfterCloseDoesNotThrow() {
        // Regression: the zero-subscriber DeadEvent publish during a post-close
        // drain hit requireOpen() and threw inside DeferScope.drain().
        EventBus bus = new EventBus(Freeway.create());

        assertDoesNotThrow(() -> Defer.within(() -> {
            bus.publish(new PostCreatedEvent(new Post("x")));
            bus.close();

        }), "a post-close drain must be a silent no-op, not a spurious failure");
    }

    @Test
    void deferredAsyncPublishAfterCloseIsSilentNoOp() {
        // Regression: the deferred publishAsync lambda called executor() during
        // a post-close drain; executor() hits requireOpen() and threw, so
        // DeferScope.drain logged a spurious WARN. Must be a silent no-op, like
        // the sync path (dispatchEvent checks closed).
        EventBus bus = new EventBus(Freeway.create());

        assertDoesNotThrow(() -> Defer.within(() -> {
            bus.publishAsync(new PostCreatedEvent(new Post("x")));
            bus.close();

        }), "a deferred async publish draining after close must not throw");
    }

    @Test
    void deferredAsyncTopicPublishAfterCloseIsSilentNoOp() {
        EventBus bus = new EventBus(Freeway.create());

        assertDoesNotThrow(() -> Defer.within(() -> {
            bus.publishAsync("topic", "payload");
            bus.close();

        }), "a deferred async topic publish draining after close must not throw");
    }

    @Test
    void deferredOrderedPublishAfterCloseIsSilentNoOp() {
        EventBus bus = new EventBus(Freeway.create());

        assertDoesNotThrow(() -> Defer.within(() -> {
            bus.publishOrdered("key", new PostCreatedEvent(new Post("x")));
            bus.close();

        }), "a deferred ordered publish draining after close must not throw");
    }

    @Test
    void deferredAsyncPublishBeforeCloseStillDelivers() throws Exception {
        // The closed-guard must not affect the healthy path: an async publish
        // deferred inside a Defer scope and drained while the bus is still open
        // must be dispatched normally after commit.
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("event")))
        );
        EventBus bus = new EventBus(container);

        Defer.within(() -> {
            bus.publishAsync(new PostCreatedEvent(new Post("x")));
        });
        Await.until(2000, () -> log.size() == 1);

        assertEquals(List.of("event"), log,
            "a deferred async event draining while open must still deliver");
        bus.close();
    }

    @Test
    void stringTopicPublishInsideDeferScopeIsDeferred() {
        List<String> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe("topic", p -> log.add((String) p));

        Defer.within(() -> {
            bus.publish("topic", "hello");
            assertTrue(log.isEmpty());
        });

        assertEquals(List.of("hello"), log);
    }

    @Test
    void publishOutsideDeferScopeStillImmediate() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> log.add("event")))
        );
        EventBus bus = new EventBus(container);

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(List.of("event"), log);
    }

    // ==================== close semantics / late contributions ====================

    @Test
    void publishAfterCloseThrows() {
        Container container = Freeway.create(binder -> {});
        EventBus bus = container.get(EventBus.class);
        bus.close();

        assertThrows(IllegalStateException.class,
            () -> bus.publish(new PostCreatedEvent(new Post("x"))));
        assertThrows(IllegalStateException.class,
            () -> bus.publish("topic", "payload"));
        assertThrows(IllegalStateException.class,
            () -> bus.publishAsync(new PostCreatedEvent(new Post("x"))));
        assertThrows(IllegalStateException.class,
            () -> bus.publishAsync("topic", "payload"));
        assertThrows(IllegalStateException.class,
            () -> bus.subscribe(PostCreatedEvent.class, e -> {}));
        assertThrows(IllegalStateException.class,
            () -> bus.subscribe("topic", p -> {}));
        assertThrows(IllegalStateException.class,
            () -> bus.setEventBridge((t, e) -> {}));
        container.close();
    }

    @Test
    void closeIsIdempotent() {
        Container container = Freeway.create(binder -> {});
        EventBus bus = container.get(EventBus.class);
        bus.close();
        assertDoesNotThrow(() -> bus.close());
        assertDoesNotThrow(() -> bus.close());
        container.close();
    }

    @Test
    void publishNullEventThrows() {
        Container container = Freeway.create(binder -> {});
        EventBus bus = container.get(EventBus.class);
        assertThrows(NullPointerException.class, () -> bus.publish((Object) null));
        container.close();
    }

    @Test
    void moduleSubscriberAddedAfterFirstPublishIsPickedUp() {
        Container container = Freeway.create(binder -> {});
        EventBus bus = container.get(EventBus.class);
        AtomicBoolean received = new AtomicBoolean();

        bus.publish(new PostCreatedEvent(new Post("first"))); // builds the index
        container.extension(EventSubscriber.class).add(
            null,
            EventSubscriber.of(PostCreatedEvent.class, e -> received.set(true))
        );
        bus.publish(new PostCreatedEvent(new Post("second")));

        assertTrue(received.get(),
            "module subscriber added after the first publish must receive events");
        container.close();
    }

    @Test
    void deadEventIsNotBridged() {
        Container container = Freeway.create(binder -> {});
        EventBus bus = container.get(EventBus.class);
        List<String> bridged = new ArrayList<>();
        bus.setEventBridge(
            (topic, event) -> bridged.add(topic + "=" + event.getClass().getSimpleName())
        );

        bus.publish(new PostCreatedEvent(new Post("x"))); // zero subscribers -> DeadEvent

        assertFalse(bridged.stream().anyMatch(s -> s.startsWith("DeadEvent")),
            "DeadEvent diagnostics must not reach the MQ bridge: " + bridged);
        assertEquals(1, bridged.size(),
            "the original event should still be bridged: " + bridged);
        container.close();
    }

    @Test
    void deadEventBypassesDefer() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(DeadEvent.class, e -> log.add("dead")))
        );
        EventBus bus = new EventBus(container);

        Defer.within(() -> {
            // Publish an event with zero subscribers — DeadEvent should fire immediately
            bus.publish("no-subscribers-for-this");
        });

        assertFalse(log.isEmpty(), "DeadEvent must fire immediately, not be deferred");
    }

    /** Polls until the condition holds, bounding CI flakiness from fixed sleeps. */
}
