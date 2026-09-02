package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Extension;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Internal subscription registry for {@link EventBus}. Kept in the same
 * package because {@link Subscription} exposes package-private construction
 * and dispatch methods.
 */
final class EventSubscriptionIndex {

    private final Container container;
    private final Map<Class<?>, List<Subscription<?>>> runtimeSubs =
        new ConcurrentHashMap<>();
    private final Map<String, List<Subscription<?>>> runtimeTopicSubs =
        new ConcurrentHashMap<>();
    private volatile ModuleIndex moduleIndex;

    EventSubscriptionIndex(Container container) {
        this.container = container;
    }

    List<Consumer<Object>> classHandlers(Class<?> eventType) {
        ensureIndexed();
        ModuleIndex idx = moduleIndex;
        if (idx == null) {
            return List.of();
        }
        return matchingSubscriptions(idx.classIdx(), eventType);
    }

    List<Consumer<Object>> topicHandlers(String topic) {
        ensureIndexed();
        ModuleIndex idx = moduleIndex;
        List<Consumer<Object>> subs = idx != null ? idx.topicIdx().get(topic) : null;
        return subs != null ? subs : List.of();
    }

    List<Subscription<?>> runtimeClassSubs(Class<?> eventType) {
        return matchingSubscriptions(runtimeSubs, eventType);
    }

    List<Subscription<?>> runtimeTopicSubs(String topic) {
        return runtimeTopicSubs.getOrDefault(topic, List.of());
    }

    <E> Subscription<E> subscribeClass(Class<E> eventType, Consumer<E> handler) {
        Subscription<E> sub = new Subscription<>(eventType, handler);
        runtimeSubs.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    Subscription<Object> subscribeTopic(String topic, Consumer<Object> handler) {
        Subscription<Object> sub = new Subscription<>(Object.class, handler, topic);
        runtimeTopicSubs.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    void unsubscribe(Subscription<?> sub) {
        if (sub.topic() != null) {
            runtimeTopicSubs.computeIfPresent(sub.topic(), (key, subs) -> {
                subs.remove(sub);
                return subs.isEmpty() ? null : subs;
            });
        } else {
            runtimeSubs.computeIfPresent(sub.eventType(), (key, subs) -> {
                subs.remove(sub);
                return subs.isEmpty() ? null : subs;
            });
        }
    }

    void clearRuntime() {
        runtimeTopicSubs.clear();
        runtimeSubs.clear();
        moduleIndex = null;
    }

    private void ensureIndexed() {
        Extension<?> ext = container.extension(EventSubscriber.class);
        long version = ext.version();
        ModuleIndex idx = moduleIndex;
        if (idx != null && idx.version() == version) {
            return;
        }
        synchronized (this) {
            idx = moduleIndex;
            version = ext.version();
            if (idx != null && idx.version() == version) {
                return;
            }
            var classIdx = new HashMap<Class<?>, List<Consumer<Object>>>();
            var topicIdx = new HashMap<String, List<Consumer<Object>>>();
            for (Object entry : ext.all()) {
                if (!(entry instanceof EventSubscriber<?> sub)) {
                    continue;
                }
                Consumer<Object> handler = adapt(sub);
                if (sub.topic() == null) {
                    classIdx.computeIfAbsent(sub.eventType(), k -> new ArrayList<>()).add(handler);
                } else {
                    topicIdx.computeIfAbsent(sub.topic(), k -> new ArrayList<>()).add(handler);
                }
            }
            moduleIndex = new ModuleIndex(classIdx, topicIdx, version);
        }
    }

    private static <T> List<T> matchingSubscriptions(
        Map<Class<?>, List<T>> index,
        Class<?> eventType
    ) {
        List<T> direct = index.get(eventType);
        List<T> result = direct != null ? new ArrayList<>(direct) : null;
        for (Class<?> sup : SUPER_TYPES.get(eventType)) {
            List<T> subs = index.get(sup);
            if (subs != null) {
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.addAll(subs);
            }
        }
        return result != null ? result : List.of();
    }

    private static final ClassValue<List<Class<?>>> SUPER_TYPES =
        new ClassValue<>() {
            @Override
            protected List<Class<?>> computeValue(Class<?> type) {
                List<Class<?>> result = new ArrayList<>();
                Set<Class<?>> seen = new HashSet<>();
                Deque<Class<?>> queue = new ArrayDeque<>();
                queue.add(type);
                while (!queue.isEmpty()) {
                    Class<?> c = queue.poll();
                    Class<?> sup = c.getSuperclass();
                    if (sup != null && sup != Object.class && seen.add(sup)) {
                        result.add(sup);
                        queue.add(sup);
                    }
                    for (Class<?> iface : c.getInterfaces()) {
                        if (seen.add(iface)) {
                            result.add(iface);
                            queue.add(iface);
                        }
                    }
                }
                return List.copyOf(result);
            }
        };

    private static <E> Consumer<Object> adapt(EventSubscriber<E> sub) {
        return event -> sub.handler().accept(sub.eventType().cast(event));
    }

    private record ModuleIndex(
        Map<Class<?>, List<Consumer<Object>>> classIdx,
        Map<String, List<Consumer<Object>>> topicIdx,
        long version
    ) {}
}
