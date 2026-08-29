package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Inbound deduplication — the reason an event carries one identity across
 * transports.
 *
 * <p>A node reachable over two transports (say a WS mesh and a Kafka broker)
 * receives every event once per transport. Both copies carry the id the
 * originating bus minted, so the second arrival is recognizable and can be
 * dropped instead of delivered twice.
 */
class EventBusInboundDedupTest {

    @Test
    void secondCopyOfTheSameIdIsDropped() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);
        bus.enableInboundDeduplication(16);

        // One event, two transports, one id — one delivery.
        bus.publishInboundWithId("hello", "evt-1");
        bus.publishInboundWithId("hello", "evt-1");

        assertEquals(List.of("hello"), received,
            "the second copy must be dropped — same id, already delivered");
        container.close();
    }

    @Test
    void distinctIdsAreAllDelivered() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);
        bus.enableInboundDeduplication(16);

        bus.publishInboundWithId("one", "evt-1");
        bus.publishInboundWithId("two", "evt-2");
        bus.publishInboundWithId("three", "evt-3");

        assertEquals(List.of("one", "two", "three"), received,
            "dedup keyed on the id must not swallow distinct events");
        container.close();
    }

    @Test
    void dedupOffDeliversEveryCopy() {
        // Off by default: dedup changes delivery semantics and costs memory,
        // so it must not be a side effect of anything else.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publishInboundWithId("hello", "evt-1");
        bus.publishInboundWithId("hello", "evt-1");

        assertEquals(List.of("hello", "hello"), received,
            "with no window armed, both copies are delivered");
        container.close();
    }

    @Test
    void disablingRestoresDuplicateDelivery() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.enableInboundDeduplication(16);
        bus.publishInboundWithId("hello", "evt-1");
        bus.disableInboundDeduplication();
        bus.publishInboundWithId("hello", "evt-1");

        assertEquals(List.of("hello", "hello"), received);
        container.close();
    }

    @Test
    void blankOrNullIdIsAlwaysDelivered() {
        // An older producer may omit the id header. There is then nothing to
        // correlate on, so the event must be delivered rather than dropped.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);
        bus.enableInboundDeduplication(16);

        bus.publishInboundWithId("a", null);
        bus.publishInboundWithId("a", null);
        bus.publishInboundWithId("b", "");
        bus.publishInboundWithId("b", "");

        assertEquals(List.of("a", "a", "b", "b"), received,
            "an event with no identity must never be deduped away");
        container.close();
    }

    @Test
    void windowIsBounded() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);
        bus.enableInboundDeduplication(2);

        bus.publishInboundWithId("a", "id-a");
        bus.publishInboundWithId("b", "id-b");
        bus.publishInboundWithId("c", "id-c");      // evicts id-a
        bus.publishInboundWithId("a again", "id-a"); // evicted, so delivered

        assertEquals(List.of("a", "b", "c", "a again"), received,
            "a capacity-2 window remembers only the last two ids");
        container.close();
    }

    @Test
    void topicChannelDedupsToo() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe("orders", payload -> received.add(String.valueOf(payload)));
        bus.enableInboundDeduplication(16);

        bus.publishInboundWithId("orders", "first", "evt-1");
        bus.publishInboundWithId("orders", "second", "evt-1");

        assertEquals(List.of("first"), received,
            "the topic channel dedups on the same wire id");
        container.close();
    }

    @Test
    void localPublishesAreNeverDeduped() {
        // Only inbound traffic carries a wire id; a local publish has none,
        // so arming the window must not start swallowing local events.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);
        bus.enableInboundDeduplication(16);

        bus.publish("local");
        bus.publish("local");

        assertEquals(List.of("local", "local"), received);
        container.close();
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        assertThrows(IllegalArgumentException.class,
            () -> bus.enableInboundDeduplication(0));
        assertThrows(IllegalArgumentException.class,
            () -> bus.enableInboundDeduplication(-1));
        container.close();
    }
}
