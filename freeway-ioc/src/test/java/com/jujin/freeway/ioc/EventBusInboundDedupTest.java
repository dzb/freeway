package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.scoped.Defer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbound deduplication — the reason an event carries one identity across
 * transports.
 *
 * <p>A node reachable over two transports (say a WS mesh and a Kafka broker)
 * receives every event once per transport. Both copies carry the id the
 * originating bus minted, so the second arrival is recognizable and can be
 * dropped instead of delivered twice.
 *
 * <p>Dedup is armed at composition time, by contributing an
 * {@link EventBridgePolicy} — there is no mid-flight toggle.
 */
class EventBusInboundDedupTest {

    /** A container whose bus carries a dedup window of the given capacity. */
    private static Container busWithDedup(int capacity) {
        return Freeway.create(binder ->
            binder.contribute(EventBridgePolicy.class).add(new EventBridgePolicy(capacity)));
    }

    @Test
    void secondCopyOfTheSameIdIsDropped() {
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        // One event, two transports, one id — one delivery.
        bus.publishInbound("hello", "evt-1");
        bus.publishInbound("hello", "evt-1");

        assertEquals(List.of("hello"), received,
            "the second copy must be dropped — same id, already delivered");
        container.close();
    }

    @Test
    void distinctIdsAreAllDelivered() {
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publishInbound("one", "evt-1");
        bus.publishInbound("two", "evt-2");
        bus.publishInbound("three", "evt-3");

        assertEquals(List.of("one", "two", "three"), received,
            "dedup keyed on the id must not swallow distinct event");
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

        bus.publishInbound("hello", "evt-1");
        bus.publishInbound("hello", "evt-1");

        assertEquals(List.of("hello", "hello"), received,
            "with no window armed, both copies are delivered");
        container.close();
    }

    @Test
    void zeroCapacityPolicyDisablesDedup() {
        // Capacity is composition-time: zero (like absence) means off.
        Container container = busWithDedup(0);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publishInbound("hello", "evt-1");
        bus.publishInbound("hello", "evt-1");

        assertEquals(List.of("hello", "hello"), received);
        container.close();
    }

    @Test
    void duplicatePoliciesFailLoudly() {
        // Two capacities is a composition error, not a "last wins" — the bus
        // is built lazily, so the failure surfaces on first resolution.
        Container container = Freeway.create(binder -> {
            binder.contribute(EventBridgePolicy.class).add(new EventBridgePolicy(16));
            binder.contribute(EventBridgePolicy.class).add(new EventBridgePolicy(32));
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> container.get(EventBus.class),
            "two dedup policies must fail instead of silently picking one");
        assertTrue(ex.getMessage().contains("Multiple EventBridgePolicy"),
            "the error must name the cause, got: " + ex.getMessage());
        container.close();
    }

    @Test
    void blankOrNullIdIsAlwaysDelivered() {
        // An older producer may omit the id header. There is then nothing to
        // correlate on, so the event must be delivered rather than dropped.
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publishInbound("a", null);
        bus.publishInbound("a", null);
        bus.publishInbound("b", "");
        bus.publishInbound("b", "");

        assertEquals(List.of("a", "a", "b", "b"), received,
            "an event with no identity must never be deduped away");
        container.close();
    }

    @Test
    void windowIsBounded() {
        Container container = busWithDedup(2);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publishInbound("a", "id-a");
        bus.publishInbound("b", "id-b");
        bus.publishInbound("c", "id-c");      // evicts id-a
        bus.publishInbound("a again", "id-a"); // evicted, so delivered

        assertEquals(List.of("a", "b", "c", "a again"), received,
            "a capacity-2 window remembers only the last two ids");
        container.close();
    }

    @Test
    void topicChannelDedupsToo() {
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe("orders", payload -> received.add(String.valueOf(payload)));

        bus.publishInbound("orders", "first", "evt-1");
        bus.publishInbound("orders", "second", "evt-1");

        assertEquals(List.of("first"), received,
            "the topic channel dedups on the same wire id");
        container.close();
    }

    @Test
    void localPublishesAreNeverDeduped() {
        // Only inbound traffic carries a wire id; a local publish has none,
        // so arming the window must not start swallowing local event.
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publish("local");
        bus.publish("local");

        assertEquals(List.of("local", "local"), received);
        container.close();
    }

    @Test
    void rollbackLeavesIdUnclaimedSoRedeliveryIsAccepted() {
        // Regression: the id used to be claimed at publish time, before the
        // Defer buffer. A rollback discarded the dispatch but kept the id
        // burned, so the broker's redelivery of the same wire id was dropped
        // as a "duplicate" — permanent event loss.
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        assertThrows(RuntimeException.class, () -> {
            Defer.within(() -> {
                bus.publishInbound("hello", "evt-1");
                throw new RuntimeException("rollback");
            });
        });
        assertEquals(List.of(), received,
            "the rolled-back publish must not dispatch");

        // Broker redelivers the same wire id after the failed transaction.
        bus.publishInbound("hello", "evt-1");

        assertEquals(List.of("hello"), received,
            "redelivery after rollback must be accepted — the id was never dispatched");
        container.close();
    }

    @Test
    void committedInboundStillDedupsRedelivery() {
        // The claim merely moved later, not vanished: once the deferred
        // dispatch runs, the id is claimed and a later copy is dropped.
        Container container = busWithDedup(16);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        Defer.within(() -> bus.publishInbound("hello", "evt-1"));
        bus.publishInbound("hello", "evt-1"); // duplicate after commit

        assertEquals(List.of("hello"), received,
            "a committed id stays claimed — the second copy is dropped");
        container.close();
    }

    @Test
    void nonPositiveCapacityDisablesInsteadOfRejecting() {
        Container container = busWithDedup(-1);
        EventBus bus = container.get(EventBus.class);
        List<String> received = new ArrayList<>();
        bus.subscribe(String.class, received::add);

        bus.publishInbound("hello", "evt-1");
        bus.publishInbound("hello", "evt-1");

        assertEquals(List.of("hello", "hello"), received,
            "a non-positive capacity releases the window and delivers both copies");
        container.close();
    }
}
