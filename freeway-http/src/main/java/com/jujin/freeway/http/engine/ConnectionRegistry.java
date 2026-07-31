package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.engine.http11.Http11Connection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live connections so the server handle can drain or force-close
 * them during shutdown. Each connection is registered by its session on
 * start and unregistered when the session ends.
 */
final class ConnectionRegistry {

    private final Set<Http11Connection> active = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    void register(Http11Connection connection) {
        active.add(connection);
    }

    void unregister(Http11Connection connection) {
        active.remove(connection);
    }

    int activeCount() {
        return active.size();
    }

    /** Signals sessions to finish the in-flight request and close. */
    void beginShutdown() {
        stopping = true;
    }

    boolean isStopping() {
        return stopping;
    }

    /** Force-closes every remaining connection (after the grace window). */
    void closeAll() {
        for (Http11Connection connection : active) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // best-effort force close
            }
        }
    }
}
