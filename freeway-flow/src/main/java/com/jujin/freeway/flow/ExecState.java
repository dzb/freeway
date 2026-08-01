package com.jujin.freeway.flow;

import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-execution working state of a {@link FlowExchanger}: stacks, counters,
 * and variables used while a flow (or sub-graph call) is running.
 *
 * <p>Shared across {@link FlowExchanger#copy()} boundaries so loop and
 * inclusive-gateway bookkeeping survives sub-graph switches.
 *
 * @author noear
 * @since 3.0
 */
public class ExecState {
    static final String ROOT = "_ROOT";

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final Map<String, Stack> stacks = new ConcurrentHashMap<>();
    private final Map<String, Object> vars = new ConcurrentHashMap<>();

    /**
     * 栈获取
     */
    @SuppressWarnings("unchecked")
    public <T> Stack<T> stack(Graph graph, String key) {
        return stacks.computeIfAbsent(graph.getId() + "/" + key, k -> new Stack<>());
    }

    /**
     * 计数获取
     */
    public int count(Graph graph, String key) {
        return counts.computeIfAbsent(graph.getId() + "/" + key, k -> new AtomicInteger(0)).get();
    }

    public int count(String key) {
        return counts.computeIfAbsent(ROOT + "/" + key, k -> new AtomicInteger(0)).get();
    }

    /**
     * 计数设置
     */
    public void countSet(Graph graph, String key, int value) {
        counts.computeIfAbsent(graph.getId() + "/" + key, k -> new AtomicInteger(0)).set(value);
    }

    public void countSet(String key, int value) {
        counts.computeIfAbsent(ROOT + "/" + key, k -> new AtomicInteger(0)).set(value);
    }

    /**
     * 计数增量
     */
    public int countIncr(Graph graph, String key) {
        return counts.computeIfAbsent(graph.getId() + "/" + key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public int countIncr(String key) {
        return counts.computeIfAbsent(ROOT + "/" + key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public int countIncr(String key, int delta) {
        return counts.computeIfAbsent(ROOT + "/" + key, k -> new AtomicInteger(0)).addAndGet(delta);
    }

    public Map<String, Object> vars() {
        return vars;
    }

    @SuppressWarnings("unchecked")
    public <T> T varAs(String key) {
        return (T) vars.get(key);
    }

    @Override
    public String toString() {
        return "ExecState{" +
                "counts=" + counts +
                ", stacks=" + stacks +
                '}';
    }
}
