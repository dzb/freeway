package com.jujin.freeway.cloud.observe.internal;

import com.jujin.freeway.cloud.observe.MetricsSnapshot;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Serves the {@code /metrics} route from the active {@link MetricsSnapshot}
 * binding. {@code ObserveModule} contributes this route and binds its
 * own {@code MetricsDefault} registry under both {@code Metrics} and
 * {@code MetricsSnapshot}, so the endpoint always renders the same registry
 * the framework counters record into. A replacement metrics backend installs
 * its own registry bindings and route — this handler does not follow a
 * swapped-in {@code Metrics} automatically.
 */
public final class MetricsHandler implements RouteHandler {

    private final MetricsSnapshot snapshot;

    public MetricsHandler(MetricsSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void handle(HttpExchange ctx) throws Exception {
        ctx.setHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ctx.sendText(HttpStatus.OK, snapshot.prometheusText());
    }
}
