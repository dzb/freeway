package com.jujin.freeway.ioc;

import org.junit.jupiter.api.Test;

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
}
