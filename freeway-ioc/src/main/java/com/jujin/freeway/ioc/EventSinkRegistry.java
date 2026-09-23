package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal registry of {@link EventSink} instances for
 * {@link com.jujin.freeway.ioc.EventBus}.
 *
 * <p>Copy-on-write behind a volatile reference: the publish hot path
 * ({@code isEmpty}/{@code snapshot}) is a plain read — no lock, no copy —
 * while add/remove/clear (rare, lifecycle-bound) build the next immutable
 * list under a monitor.</p>
 */
final class EventSinkRegistry {

    private volatile List<EventSink> sinks = List.of();

    public void add(EventSink sink) {
        synchronized (this) {
            for (EventSink installed : sinks) {
                if (installed == sink) {
                    return;
                }
            }
            List<EventSink> next = new ArrayList<>(sinks.size() + 1);
            next.addAll(sinks);
            next.add(sink);
            sinks = List.copyOf(next);
        }
    }

    public boolean remove(EventSink sink) {
        synchronized (this) {
            for (int i = 0; i < sinks.size(); i++) {
                if (sinks.get(i) == sink) { // identity, like add
                    List<EventSink> next = new ArrayList<>(sinks);
                    next.remove(i);
                    sinks = List.copyOf(next);
                    return true;
                }
            }
            return false;
        }
    }

    public boolean isEmpty() {
        return sinks.isEmpty();
    }

    /** The live immutable snapshot — callers iterate, never mutate. */
    public List<EventSink> snapshot() {
        return sinks;
    }

    public void clear() {
        synchronized (this) {
            sinks = List.of();
        }
    }
}
