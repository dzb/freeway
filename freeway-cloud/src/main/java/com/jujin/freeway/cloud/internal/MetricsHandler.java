package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Exposes the {@link Metrics} registry as Prometheus text format on
 * {@code /metrics}.
 */
public final class MetricsHandler implements RouteHandler {

    private final Metrics registry;

    public MetricsHandler(Metrics registry) {
        this.registry = registry;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        ctx.setHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        if (registry instanceof MetricsDefault def) {
            ctx.send(200, def.prometheusText());
        } else {
            ctx.send(200, "# metrics unavailable for " + registry.getClass().getName());
        }
    }
}
