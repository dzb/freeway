package com.jujin.freeway.ioc;
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


    // ==================== event types ====================

/** EventBusBridgeTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusBridgeTest {
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
        bus.addEventBridge((topic, event) -> {
            throw new IllegalStateException("mq down");
        });

        assertDoesNotThrow(() -> bus.publish(new PostCreatedEvent(new Post("x"))),
            "a failing bridge must be isolated like a failing subscriber");
        bus.close();
    }

    @Test
    void addEventBridgeFansOutToEveryBridge() {
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> { }))
        );
        EventBus bus = new EventBus(container);
        var first = new java.util.ArrayList<String>();
        var second = new java.util.ArrayList<String>();
        bus.addEventBridge((topic, event) -> first.add(event.getClass().getSimpleName()));
        bus.addEventBridge((topic, event) -> second.add(event.getClass().getSimpleName()));

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(List.of("PostCreatedEvent"), first, "first bridge sees the event");
        assertEquals(List.of("PostCreatedEvent"), second, "second bridge sees the event too");
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
        bus.addEventBridge((topic, event) -> bridged.add(event.getClass().getSimpleName()));

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
        bus.addEventBridge((topic, event) -> bridged.add(event.getClass().getSimpleName()));

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
        bus.addEventBridge((topic, event) -> bridged.add(topic));

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
        bus.addEventBridge(new EventBridge() {
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
    void addEventBridgeIsIdempotentByIdentity() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        EventBridge bridge = (topic, event) -> seen.add(topic);
        bus.addEventBridge(bridge);
        bus.addEventBridge(bridge);

        bus.publish("t", "payload");

        assertEquals(List.of("t"), seen,
            "the same bridge instance installed twice must not receive twice");
        container.close();
    }

    @Test
    void removeEventBridgeDetachesTheChannel() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        EventBridge bridge = (topic, event) -> seen.add(topic);
        bus.addEventBridge(bridge);

        assertTrue(bus.removeEventBridge(bridge), "an installed bridge is removable");
        assertFalse(bus.removeEventBridge(bridge), "removing twice is a no-op");

        bus.publish("t", "payload");
        assertTrue(seen.isEmpty(), "a removed bridge must receive nothing: " + seen);
        container.close();
    }

    @Test
    void closeDetachesBridges() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        bus.addEventBridge((topic, event) -> seen.add(topic));

        bus.close();

        // Removal stays callable during shutdown so a module's stop hook can
        // release its channel.
        assertDoesNotThrow(() -> bus.removeEventBridge((topic, event) -> { }));
        assertTrue(seen.isEmpty(), "close must not leave bridges attached");
        container.close();
    }

    @Test
    void failingBridgeDoesNotStopTheNextOne() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        bus.addEventBridge((topic, event) -> { throw new IllegalStateException("down"); });
        bus.addEventBridge((topic, event) -> seen.add(topic));

        bus.publish("t", "payload");

        assertEquals(List.of("t"), seen,
            "a throwing bridge must not starve the bridges after it");
        container.close();
    }

    @Test
    void everyBridgeReceivesTheSameEventId() {
        // The whole point of the 4-arg send: an event fanned out to N
        // transports must carry ONE identity, or the copies cannot be
        // correlated by whoever receives two of them.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        IdRecordingBridge first = new IdRecordingBridge();
        IdRecordingBridge second = new IdRecordingBridge();
        bus.addEventBridge(first);
        bus.addEventBridge(second);

        bus.publish("t", "payload");
        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(2, first.ids.size(), "topic + class dispatch");
        assertEquals(first.ids, second.ids,
            "both bridges must see the same ids — a fresh id per bridge would "
                + "make the two copies of one event unrelatable");
        assertFalse(first.ids.get(0).isBlank());
        // One id per dispatch, not one per event: the two publishes differ.
        assertNotEquals(first.ids.get(0), first.ids.get(1));
        container.close();
    }

    @Test
    void bridgesSeeNoIdWhenNoneCanBeFannedOut() {
        // Sanity: the id is minted per dispatch, so two publishes never
        // share one even through a single bridge.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        IdRecordingBridge bridge = new IdRecordingBridge();
        bus.addEventBridge(bridge);

        bus.publish("t", "one");
        bus.publish("t", "two");

        assertEquals(2, bridge.ids.size());
        assertNotEquals(bridge.ids.get(0), bridge.ids.get(1));
        container.close();
    }

    /** Captures the eventId the bus hands it, to assert identity sharing. */
    private static final class IdRecordingBridge implements EventBridge {
        final List<String> ids = new ArrayList<>();

        @Override
        public void send(String topic, Object event) {
            send(topic, event, Channel.CLASS, null);
        }

        @Override
        public void send(String topic, Object event, Channel channel, String eventId) {
            ids.add(eventId);
        }
    }
}
