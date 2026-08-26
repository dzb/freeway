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

/** EventBusAsyncPublishTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusAsyncPublishTest {
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
}
