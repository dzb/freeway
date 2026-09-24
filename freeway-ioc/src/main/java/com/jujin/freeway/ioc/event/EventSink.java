package com.jujin.freeway.ioc.event;

/**
 * Sink from the local event bus to an external message queue (Kafka, RabbitMQ, etc.).
 *
 * <p>One method: the bus always calls {@link #send(String, Object, Channel, String)}
 * with the dispatch channel and the id it minted once for the whole fan-out,
 * so the same logical event carries one identity across every transport it is
 * sent to. Without the shared id each sink would mint its own, and no consumer
 * could ever correlate the copies — which is what makes cross-transport dedup
 * possible at all: when one event arrives over two channels (say a WS mesh and
 * a Kafka broker), the second arrival is recognizable <em>only</em> because both
 * copies carry the same id. Implementations that have no use for the id or the
 * channel may ignore them.
 *
 * <p><b>Error contract:</b> {@code send} must not throw — the bus isolates
 * a throwing sink (warn-logged, never retried), so dispatch survives, but
 * the failure itself is the sink's to handle: log it and, for
 * connection-oriented transports, drop the channel so the transport can
 * reconnect, instead of surfacing raw exceptions to the publishing thread.
 *
 * <p><b>Reliability profiles differ per transport</b> — this interface sets
 * the floor (isolated, counted, at-most-once past the local dispatch), each
 * transport documents its own ceiling: the WS mesh is a volatile fabric
 * (a failed send drops the connection and the event with it), while a
 * broker-backed sink (Kafka) is durable (producer retries, consumer groups,
 * poison records to a dead-letter topic). Do not assume one transport's
 * guarantees on another's behalf.
 *
 * <p><b>Visit order is contribution order.</b> The bus walks sinks in the
 * order their {@code contribute(EventSink.class)} entries resolve — topological
 * when {@code before}/{@code after} constraints were declared. That sequences
 * only this JVM's fan-out loop; it is not a delivery-order promise across
 * transports.
 *
 * <p><b>No ordering across transports.</b> Every sink in a fan-out receives
 * every event, but nothing orders one transport against another — an event
 * that travels both mesh and broker arrives twice in either order (tell them
 * apart by {@code eventId}, not by arrival). Ordering exists only inside one
 * transport's own mechanism (e.g. a broker's per-key order for
 * {@code Keyed} events); the local {@code publishOrdered} channel orders
 * dispatches inside this JVM and makes no promise past it.
 */
public interface EventSink {

    /** Dispatch channel of the sent event. */
    enum Channel {
        /** Class-based dispatch: the topic is derived from the event type. */
        CLASS,
        /** String-topic dispatch: the topic carries the routing meaning. */
        TOPIC
    }

    /**
     * Sends one dispatched event.
     *
     * @param topic   the sink topic: the string topic for {@link Channel#TOPIC},
     *                the {@code @Topic} value or simple class name for {@link Channel#CLASS}
     * @param event   the event (or topic payload) that was dispatched
     * @param channel the local dispatch channel the event was published on
     * @param eventId bus-minted identity of this dispatch, shared by every
     *                sink in the fan-out; never null
     */
    void send(String topic, Object event, Channel channel, String eventId);
}
