package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.EventBridge;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal registry of {@link EventBridge} instances for
 * {@link com.jujin.freeway.ioc.EventBus}.
 */
public final class EventBridgeRegistry {

    private final List<EventBridge> bridges = new ArrayList<>();

    public synchronized void add(EventBridge bridge) {
        for (EventBridge installed : bridges) {
            if (installed == bridge) {
                return;
            }
        }
        bridges.add(bridge);
    }

    public synchronized boolean remove(EventBridge bridge) {
        for (int i = 0; i < bridges.size(); i++) {
            if (bridges.get(i) == bridge) {
                bridges.remove(i);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isEmpty() {
        return bridges.isEmpty();
    }

    public synchronized List<EventBridge> snapshot() {
        return List.copyOf(bridges);
    }

    public synchronized void clear() {
        bridges.clear();
    }
}
