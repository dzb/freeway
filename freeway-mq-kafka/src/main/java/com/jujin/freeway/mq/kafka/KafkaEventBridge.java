package com.jujin.freeway.mq.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jujin.freeway.ioc.EventBridge;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class KafkaEventBridge implements EventBridge, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventBridge.class);

    private final Producer<String, byte[]> producer;
    private final ObjectMapper mapper;

    public KafkaEventBridge(KafkaConfig config) {
        var props = new Properties();
        props.put("bootstrap.servers", config.bootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", ByteArraySerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void send(String topic, Object event) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(event);
            var record = new ProducerRecord<String, byte[]>(topic, null, bytes);
            String typeName = event.getClass().getName();
            record.headers().add("X-Event-Type", typeName.getBytes(StandardCharsets.UTF_8));
            producer.send(record, (meta, ex) -> {
                if (ex != null) LOG.warn("Kafka send failed for topic '{}'", topic, ex);
            });
        } catch (Exception ex) {
            LOG.warn("Failed to serialize event for topic '{}'", topic, ex);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
