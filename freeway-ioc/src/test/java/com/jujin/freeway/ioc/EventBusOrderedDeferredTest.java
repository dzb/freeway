package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.event.*;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.scoped.Defer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


    // ==================== event types ====================

/** EventBusOrderedDeferredTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusOrderedDeferredTest {
    @Test
    void publishOrderedDispatchesInSubmissionOrder() throws Exception {
        List<Integer> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe(Integer.class, log::add);

        for (int i = 0; i < 50; i++) {
            bus.publishOrdered(i);
        }
        Await.until(5000, () -> log.size() == 50);
        for (int i = 0; i < 50; i++) {
            assertEquals(Integer.valueOf(i), log.get(i),
                "ordered event must dispatch in submission order");
        }
        bus.close();
    }

    @Test
    void publishOrderedInsideDeferScopeKeepsOrder() throws Exception {
        List<Integer> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe(Integer.class, log::add);

        Defer.within(() -> {
            bus.publishOrdered(1);
            bus.publishOrdered(2);
            bus.publishOrdered(3);
            assertEquals(0, log.size(),
                "ordered event inside a Defer scope must wait for the scope end");
        });
        Await.until(2000, () -> log.size() == 3);

        assertEquals(List.of(1, 2, 3), log,
            "ordered event must drain in call order after the scope commits");
        bus.close();
    }

    @Test
    void publishOrderedTopicDispatchesInSubmissionOrderAfterCommit() throws Exception {
        // The ordered channel carries the topic payload form too — the
        // transaction-outbox use case often routes on a topic string.
        List<Object> log = new ArrayList<>();
        EventBus bus = new EventBus(Freeway.create());
        bus.subscribe("ordered.topic", log::add);

        Defer.within(() -> {
            bus.publishOrdered("ordered.topic", 1);
            bus.publishOrdered("ordered.topic", 2);
            assertTrue(log.isEmpty(), "ordered topic publish must wait for commit");
        });
        Await.until(2000, () -> log.size() == 2);

        assertEquals(List.of(1, 2), log,
            "ordered topic payloads must drain in submission order");
        bus.close();
    }

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
        // Regression: event buffered before close() drained after close,
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
            bus.publishOrdered(new PostCreatedEvent(new Post("x")));
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
            () -> bus.publishOrdered("topic", "payload"));
        assertThrows(IllegalStateException.class,
            () -> bus.subscribe(PostCreatedEvent.class, e -> {}));
        assertThrows(IllegalStateException.class,
            () -> bus.subscribe("topic", p -> {}));
        assertThrows(IllegalStateException.class,
            () -> container.extension(EventSubscriber.class).add(
                "late", EventSubscriber.of(PostCreatedEvent.class, e -> { })),
            "a contribution after composition is rejected like every other late add");
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
    void moduleSubscriberContributionAfterCompositionIsRejected() {
        // Contributions are composition-time only: the container seals the
        // stores when the build ends, so the subscription index builds once
        // against a frozen set. Late subscribers use bus.subscribe.
        Container container = Freeway.create(binder -> {});
        EventBus bus = container.get(EventBus.class);
        bus.publish(new PostCreatedEvent(new Post("first"))); // builds the index

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> container.extension(EventSubscriber.class).add(
                null,
                EventSubscriber.of(PostCreatedEvent.class, e -> { })
            ));
        assertTrue(ex.getMessage().contains("sealed"), ex.getMessage());
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
