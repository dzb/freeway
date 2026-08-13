package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpRequestHandler;
import com.jujin.freeway.http.HttpServerConfig;

/**
 * Bundles the shared dependencies a per-protocol session needs, so each
 * protocol handler carries one context instead of a long constructor
 * argument list.
 */
record SessionContext(
    HttpRequestHandler handler,
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
}
