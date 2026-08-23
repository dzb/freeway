package com.jujin.freeway.ioc;

/**
 * Bridge from the local event bus to an external message queue (Kafka, RabbitMQ, etc.).
 *
 * <p>Implementations may override the channel-aware {@link #send(String, Object, Channel)}
 * to stamp the wire envelope with the dispatch channel; the default delegates to
 * {@link #send(String, Object)} so existing implementations keep working unchanged.
 */
public interface EventBridge {

    /** Dispatch channel of the bridged event. */
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
}
