package com.jujin.freeway.flow;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Flow context, representing the context data of a flow instance
 *
 * @author noear
 * @since 3.0
 */
public interface FlowContext {

    static FlowContext of() {
        return new FlowContextImpl();
    }

    static FlowContext of(String instanceId) {
        return new FlowContextImpl(instanceId);
    }

    // --- serialization ---

    /** Converts to JSON (for persistence) */
    String toJson();

    // --- flow control ---

    /**
     * Interrupts this execution (global semantics): once the flag is set, all parallel
     * branches stop advancing at their next node boundary; the flag lasts until this eval
     * ends (never cleared midway, so every branch observes it). Not branch-local — the whole run stops after this call.
     */
    void interrupt();

    /** Stops execution (i.e. ends the run) */
    void stop();

    /** Whether execution is stopped */
    boolean isStopped();

    // --- trace ---

    FlowTrace trace();

    FlowContext enableTrace(boolean enable);

    NodeRecord lastRecord();

    String lastNodeId();

    // --- event bus ---

    /** Gets the event bus (topic-based pub/sub, scoped to this execution) */
    FlowEventBus eventBus();

    // --- data ---

    /** Data */
    Map<String, Object> data();

    /** Gets the flow instance id */
    default String getInstanceId() {
        return getAs("instanceId");
    }

    // --- data access ---

    default FlowContext put(String key, Object value) {
        if (value != null) data().put(key, value);
        return this;
    }

    default FlowContext putIfAbsent(String key, Object value) {
        if (value != null) data().putIfAbsent(key, value);
        return this;
    }

    default FlowContext putAll(Map<String, Object> model) {
        // Consistent with put(): null values are not stored.
        model.forEach((k, v) -> {
            if (v != null) data().put(k, v);
        });
        return this;
    }

    @SuppressWarnings("unchecked")
    default <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        return (T) data().computeIfAbsent(key, mappingFunction);
    }

    default boolean containsKey(String key) {
        return data().containsKey(key);
    }

    default Object get(String key) {
        return data().get(key);
    }

    @SuppressWarnings("unchecked")
    default <T> T getAs(String key) {
        return (T) data().get(key);
    }

    @SuppressWarnings("unchecked")
    default <T> T getOrDefault(String key, T def) {
        return (T) data().getOrDefault(key, def);
    }

    default void remove(String key) {
        data().remove(key);
    }

    // --- convenience ---

    default FlowContext then(Consumer<FlowContext> consumer) {
        consumer.accept(this);
        return this;
    }

    // --- internal (package-private usage by engine) ---

    /** @hidden used internally by the engine */
    FlowExchanger exchanger();

    /** @hidden used internally by the engine */
    void exchanger(FlowExchanger exchanger);

    /** @hidden used internally by the engine */
    void stopped(boolean stopped);
}
