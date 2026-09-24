package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.event.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** EventBusSinkTest: split from the former EventBusTest monolith (behavior-preserving move). */
class EventBusSinkTest {
    @Test
    void sinkFailureDoesNotEscapePublish() {
        // Regression: sink.send sat outside any try/catch, so a failing
        // sink escaped publish() to the caller in the immediate path while
        // the Defer path only warn-logged it — asymmetric behavior.
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> { }));
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) -> {
                    throw new IllegalStateException("mq down");
                });
        });
        EventBus bus = container.get(EventBus.class);

        assertDoesNotThrow(() -> bus.publish(new PostCreatedEvent(new Post("x"))),
            "a failing sink must be isolated like a failing subscriber");
        container.close();
    }

    @Test
    void contributedSinksFanOutToEverySink() {
        var first = new java.util.ArrayList<String>();
        var second = new java.util.ArrayList<String>();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> { }));
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) ->
                    first.add(event.getClass().getSimpleName()));
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) ->
                    second.add(event.getClass().getSimpleName()));
        });
        EventBus bus = container.get(EventBus.class);

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(List.of("PostCreatedEvent"), first, "first sink sees the event");
        assertEquals(List.of("PostCreatedEvent"), second, "second sink sees the event too");
        container.close();
    }

    @Test
    void stoppedEventIsNotSentToSink() {
        // A Stoppable event short-circuited by its subscribers must not leave
        // the process via the sink.
        List<String> sent = new ArrayList<>();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, e -> e.stop()));
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) ->
                    sent.add(event.getClass().getSimpleName()));
        });
        EventBus bus = container.get(EventBus.class);

        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(0, sent.size(),
            "a stopped event must not reach the sink");
        container.close();
    }

    @Test
    void inboundClassEventIsDeliveredLocallyButNotSentToSink() {
        // publishInbound must reach local class subscribers yet never echo
        // back through the sink — sending inbound traffic back out would loop
        // the event around the MQ indefinitely.
        List<PostCreatedEvent> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(PostCreatedEvent.class, received::add));
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) ->
                    sent.add(event.getClass().getSimpleName()));
        });
        EventBus bus = container.get(EventBus.class);

        bus.publishInbound(new PostCreatedEvent(new Post("remote")), "remote-1");

        assertEquals(List.of("remote"),
            received.stream().map(e -> e.post().title()).toList(),
            "inbound event must be delivered to local class subscribers");
        assertEquals(0, sent.size(),
            "inbound event must never be sent back out to the MQ");
        container.close();
    }

    @Test
    void inboundTopicEventIsDeliveredLocallyButNotSentToSink() {
        List<String> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) -> sent.add(topic));
        });
        EventBus bus = container.get(EventBus.class);
        bus.subscribe("order.placed", payload -> received.add(String.valueOf(payload)));

        bus.publishInbound("order.placed", "from-remote", "remote-1");

        assertEquals(List.of("from-remote"), received,
            "inbound topic event must be delivered to local topic subscribers");
        assertEquals(0, sent.size(),
            "inbound topic event must never be sent back out to the MQ");
        container.close();
    }

    @Test
    void sinkReceivesDispatchChannel() {
        // The sink must learn whether an event was published on the
        // class channel or the topic channel so adapters can stamp the
        // wire envelope accordingly (inbound dispatch must mirror it).
        List<EventSink.Channel> channels = new ArrayList<>();
        Container container = Freeway.create(binder ->
            binder.contribute(EventSink.class).add(new EventSink() {
                @Override
                public void send(
                    String topic, Object event, EventSink.Channel channel, String eventId
                ) {
                    channels.add(channel);
                }
            }));
        EventBus bus = container.get(EventBus.class);

        bus.publish(new PostCreatedEvent(new Post("x")));
        bus.publish("order.placed", "payload");

        assertEquals(
            List.of(EventSink.Channel.CLASS, EventSink.Channel.TOPIC),
            channels,
            "class event must be sent as CLASS, topic event as TOPIC");
        container.close();
    }

    @Test
    void duplicateContributionFansOutTwice() {
        // Contributions are values, not installs: contributing the same sink
        // instance twice fans out twice — the same semantics as contributing
        // the same subscriber twice. Deduplication by identity died with the
        // runtime add/remove API.
        List<String> seen = new ArrayList<>();
        EventSink sink = (topic, event, channel, eventId) -> seen.add(topic);
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSink.class).add(sink);
            binder.contribute(EventSink.class).add(sink);
        });
        EventBus bus = container.get(EventBus.class);

        bus.publish("t", "payload");

        assertEquals(List.of("t", "t"), seen,
            "each contribution is an independent channel, even for one instance");
        container.close();
    }

    @Test
    void closedBusRejectsPublish() {
        // The detach dance died with removeEventSink: a closed transport is
        // never touched because post-close publishes are rejected, not
        // best-effort no-ops.
        Container container = Freeway.create(binder -> { });
        EventBus bus = container.get(EventBus.class);
        List<String> seen = new ArrayList<>();
        bus.subscribe("t", payload -> seen.add(String.valueOf(payload)));

        bus.close();

        assertThrows(IllegalStateException.class, () -> bus.publish("t", "payload"),
            "publishing on a closed bus must fail instead of reaching sinks");
        assertTrue(seen.isEmpty(), "nothing dispatches after close: " + seen);
        container.close();
    }

    @Test
    void failingSinkDoesNotStopTheNextOne() {
        List<String> seen = new ArrayList<>();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) -> {
                    throw new IllegalStateException("down");
                });
            binder.contribute(EventSink.class)
                .add((EventSink) (topic, event, channel, eventId) -> seen.add(topic));
        });
        EventBus bus = container.get(EventBus.class);

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
        IdRecordingSink first = new IdRecordingSink();
        IdRecordingSink second = new IdRecordingSink();
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSink.class).add(first);
            binder.contribute(EventSink.class).add(second);
        });
        EventBus bus = container.get(EventBus.class);

        bus.publish("t", "payload");
        bus.publish(new PostCreatedEvent(new Post("x")));

        assertEquals(2, first.ids.size(), "topic + class dispatch");
        assertEquals(first.ids, second.ids,
            "both sinks must see the same ids — a fresh id per sink would "
                + "make the two copies of one event unrelatable");
        assertTrue(!first.ids.get(0).isBlank());
        // One id per dispatch, not one per event: the two publishes differ.
        assertNotEquals(first.ids.get(0), first.ids.get(1));
        container.close();
    }

    @Test
    void sinksSeeNoIdWhenNoneCanBeFannedOut() {
        // Sanity: the id is minted per dispatch, so two publishes never
        // share one even through a single sink.
        IdRecordingSink sink = new IdRecordingSink();
        Container container = Freeway.create(binder ->
            binder.contribute(EventSink.class).add(sink));
        EventBus bus = container.get(EventBus.class);

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
        public void send(String topic, Object event, Channel channel, String eventId) {
            ids.add(eventId);
        }
    }
}
