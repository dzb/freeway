package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.ioc.annotation.Value;
import java.util.List;

public record KafkaConfig(
    @Value("${freeway.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
    @Value("${freeway.kafka.group-id:freeway}") String groupId,
    @Value("${freeway.kafka.topics:}") List<String> topics
) {}
