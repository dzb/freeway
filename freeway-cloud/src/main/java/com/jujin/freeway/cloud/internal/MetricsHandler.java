package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.observe.MetricsSnapshot;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Serves the {@code /metrics} route from the primary {@code Metrics}
 * registry's {@link MetricsSnapshot} view. Injects the capability interface
 * — the module binding derives it from the primary registry, so a swapped-in
 * backend that can render itself is exported without touching this handler;
 * one that cannot fails the startup route resolution.
 */
public final class MetricsHandler implements RouteHandler {

    private final MetricsSnapshot snapshot;

    public MetricsHandler(MetricsSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        ctx.setHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ctx.send(200, snapshot.prometheusText());
    }
}
