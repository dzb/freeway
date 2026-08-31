package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.EventSink;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal registry of {@link EventSink} instances for
 * {@link com.jujin.freeway.ioc.EventBus}.
 */
public final class EventSinkRegistry {

    private final List<EventSink> sinks = new ArrayList<>();

    public synchronized void add(EventSink sink) {
        for (EventSink installed : sinks) {
            if (installed == sink) {
                return;
            }
        }
        sinks.add(sink);
    }

    public synchronized boolean remove(EventSink sink) {
        for (int i = 0; i < sinks.size(); i++) {
            if (sinks.get(i) == sink) {
                sinks.remove(i);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isEmpty() {
        return sinks.isEmpty();
    }

    public synchronized List<EventSink> snapshot() {
        return List.copyOf(sinks);
    }

    public synchronized void clear() {
        sinks.clear();
    }
}
