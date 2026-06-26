package com.jujin.freeway.flow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 流执行级事件总线（topic 主题式 pub/sub，对标 solon-flow 的 DamiBus 用法）
 *
 * <p>零外部依赖，内嵌于 FlowContext，作用域限定在单次流执行内。</p>
 *
 * <pre>{@code
 * FlowEventBus bus = context.eventBus();
 * Subscription sub = bus.subscribe("order.created", event -> {
 *     System.out.println("收到: " + event);
 * });
 * bus.publish("order.created", someData);
 * bus.unsubscribe(sub);
 * }</pre>
 *
 * @since 1.2.2
 */
public class FlowEventBus {

    private final Map<String, List<Subscription>> topicSubs = new ConcurrentHashMap<>();

    /**
     * 发布事件到指定主题
     */
    public void publish(String topic, Object event) {
        List<Subscription> subs = topicSubs.get(topic);
        if (subs == null) return;

        for (Subscription sub : subs) {
            try {
                sub.handler.accept(event);
            } catch (Exception e) {
                // 订阅者异常不影响其他订阅者
            }
        }
    }

    /**
     * 订阅主题，返回句柄用于取消
     */
    public Subscription subscribe(String topic, Consumer<Object> handler) {
        Subscription sub = new Subscription(topic, handler);
        topicSubs.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(Subscription sub) {
        List<Subscription> subs = topicSubs.get(sub.topic);
        if (subs != null) {
            subs.remove(sub);
        }
    }

    /**
     * 订阅句柄
     */
    public static class Subscription {
        private final String topic;
        private final Consumer<Object> handler;

        Subscription(String topic, Consumer<Object> handler) {
            this.topic = topic;
            this.handler = handler;
        }

        public String topic() { return topic; }
        public Consumer<Object> handler() { return handler; }
    }
}
