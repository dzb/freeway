package com.jujin.freeway.http.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.jujin.freeway.commons.metrics.Metrics;
/**
 * Tracks live connections so the server handle can drain or force-close
 * them during shutdown. Each connection is registered by its session on
 * start and unregistered when the session ends.
 */
final class ConnectionRegistry {

    private final Set<HttpConnection> active = ConcurrentHashMap.newKeySet();
    /** Requests currently executing on any connection (gauge source). */
    final AtomicInteger requestsInFlight = new AtomicInteger();
    private volatile boolean stopping;

    ConnectionRegistry(Metrics metrics) {
        metrics.gauge("freeway.http.connections.active", this::activeCount);
        metrics.gauge("freeway.http.requests.active", requestsInFlight::get);
    }

    void register(HttpConnection connection) {
        active.add(connection);
    }

    void unregister(HttpConnection connection) {
        active.remove(connection);
    }

    int activeCount() {
        return active.size();
    }

    /** Signals sessions to finish the in-flight request and close. */
    void beginShutdown() {
        stopping = true;
    }

    /** Runs each connection's pre-close hook (e.g. HTTP/2 GOAWAY) so peers
     *  learn about the shutdown before the connection is force-closed. */
    void preCloseAll() {
        for (HttpConnection connection : active) {
            connection.preClose();
        }
    }

    boolean isStopping() {
        return stopping;
    }

    /** Force-closes every remaining connection (after the grace window). */
    void closeAll() {
        for (HttpConnection connection : active) {
            try {
                connection.forceClose();
            } catch (Exception ignored) {
                // best-effort force close
            }
        }
    }
}
