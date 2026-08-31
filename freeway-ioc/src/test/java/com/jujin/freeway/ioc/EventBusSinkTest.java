package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** EventBusSinkTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusSinkTest {
    @Test
    void sinkFailureDoesNotEscapePublish() {
        // Regression: sink.send sat outside any try/catch, so a failing
        // sink escaped publish() to the caller in the immediate path while
        // the Defer path only warn-logged it — asymmetric behavior.
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> { }))
        );
        EventBus bus = new EventBus(container);
        bus.addEventSink((topic, event) -> {
            throw new IllegalStateException("mq down");
        });

        assertDoesNotThrow(() -> bus.publish(new PostCreatedEvent(new Post("x"))),
            "a failing sink must be isolated like a failing subscriber");
        bus.close();
    }

    @Test
    void addEventSinkFansOutToEverySink() {
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> { }))
        );
        EventBus bus = new EventBus(container);
        var first = new java.util.ArrayList<String>();
        var second = new java.util.ArrayList<String>();
        bus.addEventSink((topic, event) -> first.add(event.getClass().getSimpleName()));
        bus.addEventSink((topic, event) -> second.add(event.getClass().getSimpleName()));

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(List.of("PostCreatedEvent"), first, "first sink sees the event");
        assertEquals(List.of("PostCreatedEvent"), second, "second sink sees the event too");
        bus.close();
    }

    @Test
    void stoppedEventIsNotSentToSink() {
        // A Stoppable event short-circuited by its subscribers must not leave
        // the process via the sink.
        List<String> sent = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> e.stop()))
        );
        EventBus bus = new EventBus(container);
        bus.addEventSink((topic, event) -> sent.add(event.getClass().getSimpleName()));

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(0, sent.size(),
            "a stopped event must not reach the sink");
        bus.close();
    }

    @Test
    void inboundClassEventIsDeliveredLocallyButNotSentToSink() {
        // publishInbound must reach local class subscribers yet never echo
        // back through the sink — sending inbound traffic back out would loop
        // the event around the MQ indefinitely.
        List<PostCreatedEvent> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        Container container = Freeway.create(
            binder -> binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, received::add))
        );
        EventBus bus = new EventBus(container);
        bus.addEventSink((topic, event) -> sent.add(event.getClass().getSimpleName()));

        bus.publishInbound(new PostCreatedEvent(new Post("remote")), "remote-1");

        assertEquals(List.of("remote"),
            received.stream().map(e -> e.post().title()).toList(),
            "inbound event must be delivered to local class subscribers");
        assertEquals(0, sent.size(),
            "inbound events must never be sent back out to the MQ");
        bus.close();
    }

    @Test
    void inboundTopicEventIsDeliveredLocallyButNotSentToSink() {
        List<String> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        bus.subscribe("order.placed", payload -> received.add(String.valueOf(payload)));
        bus.addEventSink((topic, event) -> sent.add(topic));

        bus.publishInbound("order.placed", "from-remote", "remote-1");

        assertEquals(List.of("from-remote"), received,
            "inbound topic event must be delivered to local topic subscribers");
        assertEquals(0, sent.size(),
            "inbound topic events must never be sent back out to the MQ");
        bus.close();
    }

    @Test
    void sinkReceivesDispatchChannel() {
        // The sink must learn whether an event was published on the
        // class channel or the topic channel so adapters can stamp the
        // wire envelope accordingly (inbound dispatch must mirror it).
        List<EventSink.Channel> channels = new ArrayList<>();
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        bus.addEventSink(new EventSink() {
            @Override
            public void send(String topic, Object event) {
                channels.add(null);
            }

            @Override
            public void send(String topic, Object event, EventSink.Channel channel) {
                channels.add(channel);
            }
        });

        bus.publish(new PostCreatedEvent(new Post("x")));
        bus.publish("order.placed", "payload");

        assertEquals(
            List.of(EventSink.Channel.CLASS, EventSink.Channel.TOPIC),
            channels,
            "class events must be sent as CLASS, topic events as TOPIC");
        bus.close();
    }

    @Test
    void addEventSinkIsIdempotentByIdentity() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        EventSink sink = (topic, event) -> seen.add(topic);
        bus.addEventSink(sink);
        bus.addEventSink(sink);

        bus.publish("t", "payload");

        assertEquals(List.of("t"), seen,
            "the same sink instance installed twice must not receive twice");
        container.close();
    }

    @Test
    void removeEventSinkDetachesTheChannel() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        EventSink sink = (topic, event) -> seen.add(topic);
        bus.addEventSink(sink);

        assertTrue(bus.removeEventSink(sink), "an installed sink is removable");
        assertFalse(bus.removeEventSink(sink), "removing twice is a no-op");

        bus.publish("t", "payload");
        assertTrue(seen.isEmpty(), "a removed sink must receive nothing: " + seen);
        container.close();
    }

    @Test
    void closeDetachesSinks() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        bus.addEventSink((topic, event) -> seen.add(topic));

        bus.close();

        // Removal stays callable during shutdown so a module's stop hook can
        // release its channel.
        assertDoesNotThrow(() -> bus.removeEventSink((topic, event) -> { }));
        assertTrue(seen.isEmpty(), "close must not leave sinks attached");
        container.close();
    }

    @Test
    void failingSinkDoesNotStopTheNextOne() {
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        bus.addEventSink((topic, event) -> { throw new IllegalStateException("down"); });
        bus.addEventSink((topic, event) -> seen.add(topic));

        bus.publish("t", "payload");

        assertEquals(List.of("t"), seen,
            "a throwing sink must not starve the sinks after it");
        container.close();
    }

    @Test
    void everySinkReceivesTheSameEventId() {
        // The whole point of the 4-arg send: an event fanned out to N
        // transports must carry ONE identity, or the copies cannot be
        // correlated by whoever receives two of them.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        IdRecordingSink first = new IdRecordingSink();
        IdRecordingSink second = new IdRecordingSink();
        bus.addEventSink(first);
        bus.addEventSink(second);

        bus.publish("t", "payload");
        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(2, first.ids.size(), "topic + class dispatch");
        assertEquals(first.ids, second.ids,
            "both sinks must see the same ids — a fresh id per sink would "
                + "make the two copies of one event unrelatable");
        assertFalse(first.ids.get(0).isBlank());
        // One id per dispatch, not one per event: the two publishes differ.
        assertNotEquals(first.ids.get(0), first.ids.get(1));
        container.close();
    }

    @Test
    void sinksSeeNoIdWhenNoneCanBeFannedOut() {
        // Sanity: the id is minted per dispatch, so two publishes never
        // share one even through a single sink.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        IdRecordingSink sink = new IdRecordingSink();
        bus.addEventSink(sink);

        bus.publish("t", "one");
        bus.publish("t", "two");

        assertEquals(2, sink.ids.size());
        assertNotEquals(sink.ids.get(0), sink.ids.get(1));
        container.close();
    }

    /** Captures the eventId the bus hands it, to assert identity sharing. */
    private static final class IdRecordingSink implements EventSink {
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
