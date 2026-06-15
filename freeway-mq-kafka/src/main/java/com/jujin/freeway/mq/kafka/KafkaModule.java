package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.RuntimeHook;

public class KafkaModule implements Module2{

    @Override
    public void bind(Binder binder) {
        binder.bind(KafkaConfig.class).to(KafkaConfig.class);
        binder.bind(KafkaEventBridge.class).to(KafkaEventBridge.class);
        binder.bind(KafkaSubscriber.class).to(KafkaSubscriber.class);

        binder.contribute(RuntimeHook.class).add("kafka-bridge", new RuntimeHook() {
            @Override
            public void start(Container container) {
                container.get(EventBus.class).setEventBridge(container.get(KafkaEventBridge.class));
                container.get(KafkaSubscriber.class).start();
            }

            @Override
            public void stop(Container container) {
                container.get(KafkaSubscriber.class).close();
            }
        });
    }
}
