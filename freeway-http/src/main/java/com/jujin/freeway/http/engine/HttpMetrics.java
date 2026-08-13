package com.jujin.freeway.http.engine;

import java.util.function.IntSupplier;

import com.jujin.freeway.commons.metrics.Metrics;

/**
 * Owns the {@code freeway.http.*} metric names and their typed access, so
 * sessions and the engine record observability through one place instead of
 * scattering string literals.
 */
final class HttpMetrics {

    private final Metrics metrics;

    HttpMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    Metrics.Counter connectionsTotal() {
        return metrics.counter("freeway.http.connections.total");
    }

    Metrics.Counter connectionsRejected() {
        return metrics.counter("freeway.http.connections.rejected");
    }

    Metrics.Timer requestsDuration() {
        return metrics.timer("freeway.http.requests.duration");
    }

    Metrics.Counter requestsTotal() {
        return metrics.counter("freeway.http.requests.total");
    }

    Metrics.Counter responses4xx() {
        return metrics.counter("freeway.http.responses.4xx");
    }

    Metrics.Counter responses5xx() {
        return metrics.counter("freeway.http.responses.5xx");
    }

    Metrics.Counter sendfileTransfers() {
        return metrics.counter("freeway.http.sendfile.transfers");
    }

    Metrics.Counter h2Connections() {
        return metrics.counter("freeway.http.h2.connections");
    }

    Metrics.Counter websocketConnections() {
        return metrics.counter("freeway.http.websocket.connections");
    }

    void registerGauges(IntSupplier activeConnections, IntSupplier inFlight) {
        metrics.gauge("freeway.http.connections.active",
            () -> activeConnections.getAsInt());
        metrics.gauge("freeway.http.requests.active",
            () -> inFlight.getAsInt());
    }
}
