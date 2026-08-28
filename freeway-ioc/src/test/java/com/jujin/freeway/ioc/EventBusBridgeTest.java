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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        bus.setEventBridge((topic, event) -> {
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
}
