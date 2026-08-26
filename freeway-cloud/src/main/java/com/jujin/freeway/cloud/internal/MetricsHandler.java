package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Exposes {@link MetricsDefault} as Prometheus text format on
 * {@code /metrics}. Injects the concrete default — the route is contributed
 * by {@code CloudObserveModule} alongside it, so they share one lifecycle.
 * A primary override of the {@code Metrics} binding swaps the SPI instance
 * without touching this route; exporting from a foreign registry is that
 * implementation's concern.
 */
public final class MetricsHandler implements RouteHandler {

    private final MetricsDefault registry;

    public MetricsHandler(MetricsDefault registry) {
        this.registry = registry;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        ctx.setHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ctx.send(200, registry.prometheusText());
    }
}
