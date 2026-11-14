package com.jujin.freeway.http.engine;

import java.util.Map;
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

    Metrics.Timer requestsDuration(Map<String, String> labels) {
        return metrics.timer("freeway.http.requests.duration", labels);
    }

    Metrics.Counter requestsTotal(Map<String, String> labels) {
        return metrics.counter("freeway.http.requests.total", labels);
    }

    /** Bounded method dimension: unknown extension methods collapse to OTHER. */
    static String methodLabel(String method) {
        if (method == null) {
            return "OTHER";
        }
        return switch (method.toUpperCase(java.util.Locale.ROOT)) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE",
                 "CONNECT" -> method.toUpperCase(java.util.Locale.ROOT);
            default -> "OTHER";
        };
    }

    /** Bounded status dimension: 1xx..5xx, or unknown for an unset/invalid status. */
    static String statusClass(int status) {
        if (status < 100 || status >= 600) {
            return "unknown";
        }
        return (status / 100) + "xx";
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

    Metrics.Counter webSocketConnections() {
        return metrics.counter("freeway.http.websocket.connections");
    }

    void registerGauges(IntSupplier activeConnections, IntSupplier inFlight) {
        metrics.gauge("freeway.http.connections.active",
            () -> activeConnections.getAsInt());
        metrics.gauge("freeway.http.requests.active",
            () -> inFlight.getAsInt());
    }
}
