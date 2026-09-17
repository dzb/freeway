package com.jujin.freeway.flow;

import com.jujin.freeway.flow.internal.FlowContextImpl;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The data of one flow execution: the key/value map tasks read and write,
 * the run's event bus, and the stop signal.
 *
 * <p>This is execution state, not a persistence document: the engine runs
 * in-JVM and a context cannot be paused and resumed in another process.
 * {@link #toJson()} exists for logging and debugging (and for handing the
 * data to a future run explicitly), never for silent resume.</p>
 */
public interface FlowContext {

    static FlowContext of() {
        return new FlowContextImpl();
    }

    static FlowContext of(String instanceId) {
        return new FlowContextImpl(instanceId);
    }

    // --- flow control ---

    /** Ends the run at the next node boundary (all branches observe it). */
    void stop();

    /** Whether the run was stopped. */
    boolean isStopped();

    // --- event bus ---

    /** The topic-based pub/sub bus scoped to this execution. */
    FlowEventBus eventBus();

    // --- data ---

    /** The data map (a live view; branch-local writes are seen through it). */
    Map<String, Object> data();

    /** The flow instance id, or empty when none was set. */
    default String instanceId() {
        return getAs("instanceId");
    }

    // --- data access (Map vocabulary) ---

    /** Stores the value — including {@code null} (a null clears the read). */
    default FlowContext put(String key, Object value) {
        data().put(key, value);
        return this;
    }

    default FlowContext putIfAbsent(String key, Object value) {
        data().putIfAbsent(key, value);
        return this;
    }

    default FlowContext putAll(Map<String, Object> model) {
        data().putAll(model);
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

    // --- serialization (diagnostic; not a resume mechanism) ---

    /** The data map as JSON. */
    String toJson();

    // --- engine-internal ---

    /** @hidden sets the stop signal (the engine and {@link FlowExchanger#stop()}). */
    void stopped(boolean stopped);

    /**
     * @hidden Opens a branch-local write buffer on the calling thread: every
     *  write until the returned merger runs lands in the buffer, reads see
     *  buffer-then-parent. The PARALLEL dispatcher wraps each branch with
     *  this; calling the merger folds the buffer into the parent (conflict
     *  if a key this branch wrote was written differently meanwhile).
     */
    Runnable beginBranch();
}
