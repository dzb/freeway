package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.ExchangeHandler;
import com.jujin.freeway.http.HttpServerConfig;

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
    Metrics.Timer requestTimer() {
        return metrics.requestsDuration();
    }

    /**
     * Dispatches one exchange to the handler and records the shared request
     * metrics (in-flight gauge, request counter/timer, and 4xx/5xx status
     * classification). Error handling stays with each protocol session so
     * HTTP/1.1 and HTTP/2 keep their distinct failure behavior.
     */
    void executeRequest(HttpContext context) throws Exception {
        registry().requestsInFlight.incrementAndGet();
        metrics().requestsTotal().increment();
        long startNanos = System.nanoTime();
        try {
            handler().handle(context);
        } finally {
            registry().requestsInFlight.decrementAndGet();
            requestTimer().record(System.nanoTime() - startNanos);
            int status = context.status();
            if (status >= 500) {
                metrics().responses5xx().increment();
            } else if (status >= 400) {
                metrics().responses4xx().increment();
            }
        }
    }
}
