package com.jujin.freeway.ioc.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Deterministic shutdown-order tests. The container's real target cache is a
 * {@code ConcurrentHashMap}, whose iteration order is unspecified — the
 * "EventBus closed before another service" race is only reachable by luck
 * there. Injecting a {@code LinkedHashMap} pins the order so the deferral
 * contract is actually exercised.
 */
class ShutdownTest {

    @Test
    void eventBusIsClosedAfterEveryOtherService() {
        // EventBus is inserted first, so an order-unspecified drain would
        // close it before ClosingPublisher — whose close() publishes. The
        // shutdown must defer the bus and deliver the event instead.
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        var received = new CopyOnWriteArrayList<String>();
        bus.subscribe(String.class, received::add);

        Map<ServiceKey, Object> targetCache = new LinkedHashMap<>();
        targetCache.put(new ServiceKey(EventBus.class, "EventBus"), bus);
        targetCache.put(new ServiceKey(ClosingPublisher.class, "closing"),
            new ClosingPublisher(bus));

        Shutdown shutdown = new Shutdown(targetCache);
        RuntimeException failure = shutdown.close();

        assertNull(failure,
            "close() must not fail when a service close() callback publishes");
        assertEquals(List.of("closing-event"), received,
            "the event published from a close() callback must be delivered while the bus is still open");
    }

    @Test
    void eventBusIsClosedAfterPreDestroyPublishes() {
        // Same deferral for the @PreDestroy drain: a publish during PreDestroy
        // must reach subscribers, never hit a closed bus.
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        var received = new CopyOnWriteArrayList<String>();
        bus.subscribe(String.class, received::add);

        Map<ServiceKey, Object> targetCache = new LinkedHashMap<>();
        targetCache.put(new ServiceKey(EventBus.class, "EventBus"), bus);
        targetCache.put(new ServiceKey(PreDestroyPublisher.class, "preDestroy"),
            new PreDestroyPublisher(bus));

        Shutdown shutdown = new Shutdown(targetCache);
        RuntimeException failure = shutdown.close();

        assertNull(failure,
            "close() must not fail when a @PreDestroy callback publishes");
        assertEquals(List.of("pre-destroy-event"), received,
            "the event published from @PreDestroy must be delivered");
    }

    static class ClosingPublisher implements AutoCloseable {
        private final EventBus bus;

        ClosingPublisher(EventBus bus) {
            this.bus = bus;
        }

        @Override
        public void close() {
            bus.publish("closing-event");
        }
    }

    static class PreDestroyPublisher {
        private final EventBus bus;

        PreDestroyPublisher(EventBus bus) {
            this.bus = bus;
        }

        @com.jujin.freeway.ioc.annotation.PreDestroy
        void cleanup() {
            bus.publish("pre-destroy-event");
        }
    }
}
