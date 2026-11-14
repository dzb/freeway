package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.ExchangeHandler;
import com.jujin.freeway.http.HttpServerConfig;
import java.util.Map;

/**
 * Bundles the shared dependencies a per-protocol session needs, so each
 * protocol handler carries one context instead of a long constructor
 * argument list.
 */
record SessionContext(
    ExchangeHandler handler,
    JsonCodec jsonCodec,
    Coercer coercer,
    FreewayHttpEngine engine,
    HttpServerConfig config,
    ConnectionRegistry registry,
    HttpMetrics metrics
) {
    Metrics.Timer requestTimer(Map<String, String> labels) {
        return metrics.requestsDuration(labels);
    }

    /**
     * Dispatches one exchange to the handler and records the shared request
     * metrics (in-flight gauge, request counter/timer, and 4xx/5xx status
     * classification). Error handling stays with each protocol session so
     * HTTP/1.1 and HTTP/2 keep their distinct failure behavior.
     */
    void executeRequest(HttpExchange context) throws Exception {
        registry().requestsInFlight.incrementAndGet();
        String method = HttpMetrics.methodLabel(context.method());
        metrics().requestsTotal(Map.of("method", method)).increment();
        long startNanos = System.nanoTime();
        try {
            handler().handle(context);
        } finally {
            registry().requestsInFlight.decrementAndGet();
            int status = context.status();
            requestTimer(Map.of(
                    "method", method,
                    "status", HttpMetrics.statusClass(status)))
                .record(System.nanoTime() - startNanos);
            if (status >= 500) {
                metrics().responses5xx().increment();
            } else if (status >= 400) {
                metrics().responses4xx().increment();
            }
        }
    }
}
