package com.jujin.freeway.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 流执行级事件总线。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>从 Solon 的全局 DamiBus 语义迁移为绑定在 {@link FlowContext} 上的本地 pub/sub。</li>
 *   <li>订阅回调异常只隔离在当前订阅者，不向外扩散，也不影响同 topic 的其他订阅者。</li>
 *   <li>保留 topic 级发布/退订能力，目的是支持同一次 flow 执行中的通知、回放和调试。</li>
 * </ul>
 * 这样可以避免引入全局消息面，同时保持原有流内事件模型。</p>
 *
 * @since 1.2.2
 */
public class FlowEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(FlowEventBus.class);

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
                LOG.warn("Subscriber failed for topic '{}': {}", topic, e.toString());
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
