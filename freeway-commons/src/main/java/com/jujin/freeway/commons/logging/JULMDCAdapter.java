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
public final class JULMDCAdapter implements MDCAdapter {

    private final ThreadLocal<Map<String, String>> context =
        new ThreadLocal<>();
    private final ThreadLocal<Map<String, Deque<String>>> dequeMap =
        new ThreadLocal<>();

    @Override
    public void put(String key, String val) {
        context().put(key, val);
    }

    @Override
    public String get(String key) {
        return context().get(key);
    }

    @Override
    public void remove(String key) {
        context().remove(key);
    }

    @Override
    public void clear() {
        Map<String, String> ctx = context.get();
        if (ctx != null) {
            ctx.clear();
        }
        Map<String, Deque<String>> deques = dequeMap.get();
        if (deques != null) {
            deques.clear();
        }
    }

    @Override
    public Map<String, String> getCopyOfContextMap() {
        Map<String, String> map = context.get();
        if (map == null || map.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    @Override
    public void setContextMap(Map<String, String> contextMap) {
        context.set(new HashMap<>(contextMap));
    }

    @Override
    public void pushByKey(String key, String value) {
        deques()
            .computeIfAbsent(key, ignored -> new ArrayDeque<>())
            .push(value);
    }

    @Override
    public String popByKey(String key) {
        Map<String, Deque<String>> map = dequeMap.get();
        if (map == null) {
            return null;
        }
        Deque<String> deque = map.get(key);
        if (deque != null && !deque.isEmpty()) {
            return deque.pop();
        }
        return null;
    }

    @Override
    public Deque<String> getCopyOfDequeByKey(String key) {
        Map<String, Deque<String>> map = dequeMap.get();
        if (map == null) {
            return null;
        }
        Deque<String> deque = map.get(key);
        if (deque == null || deque.isEmpty()) {
            return null;
        }
        return new ArrayDeque<>(deque);
    }

    @Override
    public void clearDequeByKey(String key) {
        Map<String, Deque<String>> map = dequeMap.get();
        if (map == null) {
            return;
        }
        Deque<String> deque = map.get(key);
        if (deque != null) {
            deque.clear();
        }
    }

    private Map<String, String> context() {
        Map<String, String> map = context.get();
        if (map == null) {
            map = new HashMap<>();
            context.set(map);
        }
        return map;
    }

    private Map<String, Deque<String>> deques() {
        Map<String, Deque<String>> map = dequeMap.get();
        if (map == null) {
            map = new HashMap<>();
            dequeMap.set(map);
        }
        return map;
    }
}
