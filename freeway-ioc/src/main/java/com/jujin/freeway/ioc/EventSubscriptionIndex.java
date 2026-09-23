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

    /**
     * Both class-handler groups for one dispatch, resolved in a single walk
     * of the supertype hierarchy — the hot path used by
     * {@code EventDispatcher.dispatchEvent}.
     *
     * <p>Lists returned here are safe to iterate without copying: module
     * lists are built once at composition and never mutated, runtime lists
     * are {@link CopyOnWriteArrayList}. A caller must not mutate them.</p>
     */
    ClassSubs classSubs(Class<?> eventType) {
        ensureIndexed();
        ModuleIndex idx = moduleIndex;
        Map<Class<?>, List<Consumer<Object>>> moduleIdx =
            idx != null ? idx.classIdx() : Map.of();
        List<Consumer<Object>> module = moduleIdx.get(eventType);
        List<Subscription<?>> runtime = runtimeSubs.get(eventType);
        List<Consumer<Object>> moduleMore = null;
        List<Subscription<?>> runtimeMore = null;
        for (Class<?> sup : SUPER_TYPES.get(eventType)) {
            List<Consumer<Object>> m = moduleIdx.get(sup);
            if (m != null) {
                moduleMore = moduleMore != null ? concat(moduleMore, m) : m;
            }
            List<Subscription<?>> r = runtimeSubs.get(sup);
            if (r != null) {
                runtimeMore = runtimeMore != null ? concat(runtimeMore, r) : r;
            }
        }
        return new ClassSubs(merge(module, moduleMore), merge(runtime, runtimeMore));
    }

    List<Consumer<Object>> topicHandlers(String topic) {
        ensureIndexed();
        ModuleIndex idx = moduleIndex;
        List<Consumer<Object>> subs = idx != null ? idx.topicIdx().get(topic) : null;
        return subs != null ? subs : List.of();
    }

    List<Subscription<?>> runtimeTopicSubs(String topic) {
        return runtimeTopicSubs.getOrDefault(topic, List.of());
    }

    /** direct handlers first, then supertype handlers in hierarchy order */
    private static <T> List<T> merge(List<T> direct, List<T> supertypeHits) {
        if (supertypeHits == null) {
            return direct != null ? direct : List.of();
        }
        if (direct == null) {
            return supertypeHits;
        }
        List<T> all = new ArrayList<>(direct.size() + supertypeHits.size());
        all.addAll(direct);
        all.addAll(supertypeHits);
        return all;
    }

    /** Grows the supertype-hit list; the first hit stays live until a
     *  second one forces a copy (each further hit copies again — the
     *  supertype count is tiny). */
    private static <T> List<T> concat(List<T> accumulated, List<T> next) {
        List<T> grown = new ArrayList<>(accumulated.size() + next.size());
        grown.addAll(accumulated);
        grown.addAll(next);
        return grown;
    }

    /** Module + runtime handlers matched for one event type. */
    record ClassSubs(
        List<Consumer<Object>> module,
        List<Subscription<?>> runtime
    ) {
        boolean isEmpty() {
            return module.isEmpty() && runtime.isEmpty();
        }
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
        // Contributions are sealed at composition — the store never changes
        // again, so the index builds once (or after clearRuntime() nulls it).
        if (moduleIndex != null) {
            return;
        }
        synchronized (this) {
            if (moduleIndex != null) {
                return;
            }
            Extension<?> ext = container.extension(EventSubscriber.class);
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
            moduleIndex = new ModuleIndex(classIdx, topicIdx);
        }
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
        Map<String, List<Consumer<Object>>> topicIdx
    ) {}
}
