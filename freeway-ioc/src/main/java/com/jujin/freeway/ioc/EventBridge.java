package com.jujin.freeway.ioc;

/** Bridge from the local event bus to an external message queue (Kafka, RabbitMQ, etc.). */
public interface EventBridge {
    void send(String topic, Object event);
}
