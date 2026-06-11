package com.jujin.freeway.mq.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jujin.freeway.ioc.EventBus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

public class KafkaSubscriber implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSubscriber.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final EventBus bus;
    private final ObjectMapper mapper;
    private final KafkaConfig config;
    private volatile boolean running;

    public KafkaSubscriber(KafkaConfig config, EventBus bus) {
        this.config = config;
        this.bus = bus;
        var props = new Properties();
        props.put("bootstrap.servers", config.bootstrapServers());
        props.put("group.id", config.groupId());
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        this.consumer = new KafkaConsumer<>(props);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public void start() {
        if (config.topics().isEmpty()) return;
        running = true;
        consumer.subscribe(config.topics());
        Thread.ofVirtual().name("freeway-kafka-subscriber").start(this::pollLoop);
        LOG.info("Kafka subscriber started for topics: {}", config.topics());
    }

    private void pollLoop() {
        while (running) {
            try {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    try {
                        Object event = deserialize(record);
                        bus.publish(record.topic(), event);
                    } catch (Exception e) {
                        LOG.warn("Failed to process Kafka message from '{}'", record.topic(), e);
                    }
                }
            } catch (Exception e) {
                if (running) LOG.warn("Kafka poll failed", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object deserialize(ConsumerRecord<String, byte[]> record) throws Exception {
        String typeName = header(record, "X-Event-Type");
        if (typeName != null) {
            try {
                Class<?> type = Class.forName(typeName);
                return mapper.readValue(record.value(), type);
            } catch (ClassNotFoundException e) {
                LOG.debug("Event type not found on classpath: {}, falling back to Map", typeName);
            }
        }
        return mapper.readValue(record.value(), Map.class);
    }

    private String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    @Override
    public void close() {
        running = false;
        consumer.close();
        LOG.info("Kafka subscriber stopped");
    }
}
