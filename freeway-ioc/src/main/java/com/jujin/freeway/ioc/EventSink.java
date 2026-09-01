package com.jujin.freeway.ioc;

/**
 * Sink from the local event bus to an external message queue (Kafka, RabbitMQ, etc.).
 *
 * <p>Implementations may override the channel-aware {@link
 * #send(String, Object, Channel)} to stamp the wire envelope with the
 * dispatch channel, and the identity-carrying {@link #send(String, Object,
 * Channel, String)} to reuse the id the bus minted for this dispatch. Both
 * default to the narrower form, so existing implementations keep working
 * unchanged.
 *
 * <p><b>Error contract:</b> {@code send} must not throw — the bus isolates
 * a throwing sink (warn-logged, never retried), so dispatch survives, but
 * the failure itself is the sink's to handle: log it and, for
 * connection-oriented transports, drop the channel so the transport can
 * reconnect, instead of surfacing raw exceptions to the publishing thread.
 */
public interface EventSink {

    /** Dispatch channel of the sent event. */
    enum Channel {
        /** Class-based dispatch: the topic is derived from the event type. */
        CLASS,
        /** String-topic dispatch: the topic carries the routing meaning. */
        TOPIC
    }

    void send(String topic, Object event);

    /**
     * Channel-aware send. The default delegates to {@link #send(String, Object)}.
     *
     * @param channel the local dispatch channel the event was published on
     */
    default void send(String topic, Object event, Channel channel) {
        send(topic, event);
    }

    /**
     * Identity-carrying send. {@code eventId} is the id the bus minted once
     * for this dispatch and handed to <em>every</em> sink — so the same
     * logical event carries one identity across every transport it is
     * sent to. Without it each sink mints its own, and no consumer can
     * ever correlate the copies.
     *
     * <p>Reusing it is what makes cross-transport dedup possible at all:
     * when one event arrives over two channels (say a WS mesh and a Kafka
     * broker), the second arrival is recognizable <em>only</em> because both
     * copies carry the same id. Implementations that have no use for the id
     * can leave this default in place.
     *
     * @param channel the local dispatch channel the event was published on
     * @param eventId bus-minted identity of this dispatch; never null
     */
    default void send(String topic, Object event, Channel channel, String eventId) {
        send(topic, event, channel);
    }
}
