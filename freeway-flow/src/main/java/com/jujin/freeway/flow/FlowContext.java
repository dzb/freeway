package com.jujin.freeway.flow;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 流上下文，表示一个流实例的上下文数据
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

    /** 转为 json（用于持久化） */
    String toJson();

    // --- flow control ---

    /** 中断当前分支（如果有其它分支，仍会执行） */
    void interrupt();

    /** 停止执行（即结束运行） */
    void stop();

    /** 是否已停止 */
    boolean isStopped();

    // --- trace ---

    FlowTrace trace();

    FlowContext enableTrace(boolean enable);

    NodeRecord lastRecord();

    String lastNodeId();

    // --- event bus ---

    /** 获取事件总线（topic 主题式 pub/sub，作用域限定在本次执行内） */
    FlowEventBus eventBus();

    // --- data ---

    /** 数据 */
    Map<String, Object> data();

    /** 获取流实例id */
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

    // --- internal (package-private usage by engine) ---

    /** @hidden 由引擎内部使用 */
    FlowExchanger exchanger();

    /** @hidden 由引擎内部使用 */
    void exchanger(FlowExchanger exchanger);

    /** @hidden 由引擎内部使用 */
    void stopped(boolean stopped);
}
