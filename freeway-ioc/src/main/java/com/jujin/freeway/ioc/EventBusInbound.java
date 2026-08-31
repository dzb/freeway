package com.jujin.freeway.ioc;

/**
 * Adapter SPI for publishing events received from an external transport.
 *
 * <p>Business code should use {@link EventBus#publish(Object)} /
 * {@link EventBus#publish(String, Object)} instead. This interface is
 * intended for Freeway transport adapters (WS mesh, Kafka, etc.) that carry
 * a wire-level {@code eventId} used for cross-transport deduplication.</p>
 */
public interface EventBusInbound {

    /**
     * Publishes an inbound class event with the wire id it arrived with.
     *
     * @param event   the event received from the wire
     * @param eventId the id carried on the wire; may be {@code null} or blank
     *                when the transport does not provide one
     * @param <E>     the event type
     */
    <E> void publishInbound(E event, String eventId);

    /**
     * Publishes an inbound topic payload with the wire id it arrived with.
     *
     * @param topic   the topic received from the wire
     * @param payload the payload received from the wire
     * @param eventId the id carried on the wire; may be {@code null} or blank
     *                when the transport does not provide one
     */
    void publishInbound(String topic, Object payload, String eventId);
}
