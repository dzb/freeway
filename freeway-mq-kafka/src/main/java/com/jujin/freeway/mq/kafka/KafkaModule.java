package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;

public class KafkaModule implements Module {

    @Override
    public void bind(Binder binder) {
        binder.bind(KafkaConfig.class).to(KafkaConfig.class);
        binder.bind(KafkaEventBridge.class).to(KafkaEventBridge.class);
        binder.bind(KafkaSubscriber.class).to(KafkaSubscriber.class);
    }
}
