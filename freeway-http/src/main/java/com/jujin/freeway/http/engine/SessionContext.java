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
    Metrics.Histogram requestTimer(Map<String, String> labels) {
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
        long startNanos = System.nanoTime();
        try {
            handler().handle(context);
        } finally {
            registry().requestsInFlight.decrementAndGet();
            // The route is known only after dispatch; record in the finally so
            // every completed request carries the declared template (or the
            // bounded category the pipeline set for a short-circuit/miss).
            String route = context.routePattern();
            if (route == null || route.isBlank()) {
                route = "unmatched";
            }
            int status = context.status();
            Map<String, String> requestLabels = Map.of(
                "method", method, "route", route);
            metrics().requestsTotal(requestLabels).increment();
            requestTimer(Map.of("method", method, "route", route))
                .record(System.nanoTime() - startNanos);
            if (status >= 500) {
                metrics().responses5xx(requestLabels).increment();
            } else if (status >= 400) {
                metrics().responses4xx(requestLabels).increment();
            }
        }
    }
}
