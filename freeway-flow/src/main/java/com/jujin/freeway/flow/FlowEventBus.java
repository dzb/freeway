package com.jujin.freeway.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Flow execution-level event bus.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Migrated from Solon's global DamiBus semantics to a local pub/sub bound to {@link FlowContext}.</li>
 *   <li>Exceptions in subscription callbacks are isolated to the current subscriber — they do not propagate outward or affect other subscribers of the same topic.</li>
 *   <li>Topic-level publish/unsubscribe capability is kept to support notification, replay and debugging within the same flow execution.</li>
 * </ul>
 * This avoids introducing a global message surface while preserving the original in-flow event model.</p>
 *
 * @since 1.2.2
 */
public class FlowEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(FlowEventBus.class);

    private final Map<String, List<Subscription>> topicSubs = new ConcurrentHashMap<>();

    /**
     * Publishes an event to the given topic
     */
    public void publish(String topic, Object event) {
        List<Subscription> subs = topicSubs.get(topic);
        if (subs == null) return;

        for (Subscription sub : subs) {
            try {
                sub.handler.accept(event);
            } catch (Exception e) {
                // Isolation is deliberate, but the stack trace is essential
                // for diagnosing where the subscriber failed.
                LOG.warn("Subscriber failed for topic '{}'", topic, e);
            }
        }
    }

    /**
     * Subscribes to a topic, returning a handle for cancellation
     */
    public Subscription subscribe(String topic, Consumer<Object> handler) {
        Subscription sub = new Subscription(topic, handler);
        topicSubs.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    /**
     * Unsubscribes
     */
    public void unsubscribe(Subscription sub) {
        List<Subscription> subs = topicSubs.get(sub.topic);
        if (subs != null) {
            subs.remove(sub);
            if (subs.isEmpty()) {
                topicSubs.remove(sub.topic, subs);
            }
        }
    }

    /**
     * Clears subscriptions of all topics.
     *
     * <p>Called when a flow execution ends and the {@link FlowContext} will be reused
     * (pause/resume, multiple evals), preventing subscribers from accumulating across runs.</p>
     */
    public void clear() {
        topicSubs.clear();
    }

    /**
     * Subscription handle
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
