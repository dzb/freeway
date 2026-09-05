package com.jujin.freeway.commons.logging;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.spi.MDCAdapter;

/**
 * SLF4J {@link MDCAdapter} backed by {@link ThreadLocal}.
 *
 * <p>Context is scoped to the calling thread and is <em>not</em> propagated
 * to threads spawned from it — neither virtual threads nor pooled platform
 * threads. Callers that dispatch work to other threads must copy the context
 * explicitly (e.g. capture {@code MDC.getCopyOfContextMap()} and restore it
 * on the worker). Virtual threads clean up their own ThreadLocal state on
 * termination; platform-thread pools must clear MDC between tasks to avoid
 * leaking context across requests.
 */
final class JULMDCAdapter implements MDCAdapter {

    private final ThreadLocal<Map<String, String>> context =
        ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Map<String, Deque<String>>> dequeMap =
        ThreadLocal.withInitial(HashMap::new);

    @Override
    public void put(String key, String val) { context.get().put(key, val); }

    @Override
    public String get(String key) { return context.get().get(key); }

    @Override
    public void remove(String key) { context.get().remove(key); }

    @Override
    public void clear() {
        context.remove();
        dequeMap.remove();
    }

    @Override
    public Map<String, String> getCopyOfContextMap() {
        Map<String, String> map = context.get();
        if (map.isEmpty()) return null;
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    @Override
    public void setContextMap(Map<String, String> contextMap) {
        context.set(new HashMap<>(contextMap));
    }

    @Override
    public void pushByKey(String key, String value) {
        dequeMap.get()
            .computeIfAbsent(key, k -> new ArrayDeque<>())
            .push(value);
    }

    @Override
    public String popByKey(String key) {
        Deque<String> deque = dequeMap.get().get(key);
        return deque != null && !deque.isEmpty() ? deque.pop() : null;
    }

    @Override
    public Deque<String> getCopyOfDequeByKey(String key) {
        Deque<String> deque = dequeMap.get().get(key);
        return deque == null || deque.isEmpty() ? null : new ArrayDeque<>(deque);
    }

    @Override
    public void clearDequeByKey(String key) {
        Deque<String> deque = dequeMap.get().get(key);
        if (deque != null) deque.clear();
    }
}
