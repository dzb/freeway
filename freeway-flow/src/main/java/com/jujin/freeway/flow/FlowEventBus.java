package com.jujin.freeway.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
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
 * <p><b>Forwarding is the module's exit.</b> The bus is the execution-scoped
 * mechanism; a container-free engine has nobody to hand events to, so the sink
 * is optional and defaults to none. {@code FlowModule} installs one that
 * publishes each event on the application's {@code EventBus} under
 * {@code "flow." + topic}, which is how an application subscribes once for
 * every evaluation instead of attaching an interceptor to each context. The
 * local subscribers still run first: they are the evaluation's own audience,
 * the sink is the application's.</p>
 */
public class FlowEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(FlowEventBus.class);

    private final Map<String, List<Subscription>> topicSubs = new ConcurrentHashMap<>();

    /**
     * The module-level exit, or {@code null} when the engine has none (the
     * standalone {@code FlowEngine.create()} case). Set once per evaluation
     * by the engine that runs it.
     */
    private volatile BiConsumer<String, Object> sink;

    /**
     * Publishes an event to the given topic: the evaluation's own subscribers
     * first, then the engine's sink (when one is installed).
     */
    public void publish(String topic, Object event) {
        List<Subscription> subs = topicSubs.get(topic);
        if (subs != null) {
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
        BiConsumer<String, Object> forward = sink;
        if (forward != null) {
            try {
                forward.accept(topic, event);
            } catch (Exception e) {
                // The same isolation as a local subscriber: a broken bridge
                // must not fail the run that published the event.
                LOG.warn("Flow event sink failed for topic '{}'", topic, e);
            }
        }
    }

    /**
     * Installs the engine's sink (or clears it with {@code null}). Called by
     * the engine at the start of a top-level evaluation; the sink outlives
     * the evaluation because the context is reused by design.
     */
    void forwardTo(BiConsumer<String, Object> sink) {
        this.sink = sink;
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
     * <p>Called by the engine when a top-level eval begins and when it
     * completes, so a reused {@link FlowContext} cannot carry one run's
     * subscribers into the next.</p>
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
