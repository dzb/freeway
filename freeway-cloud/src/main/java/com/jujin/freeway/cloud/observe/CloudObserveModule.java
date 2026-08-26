package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.MetricsDefault;
import com.jujin.freeway.cloud.internal.MetricsHandler;
import com.jujin.freeway.cloud.internal.TracerDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for observability: {@link Tracer} → {@link TracerDefault}
 * (ScopedValue context + MDC display layer + span duration into
 * {@code tracer.span.duration}) and {@link Metrics} →
 * {@link MetricsDefault} (in-memory) with a Prometheus-text
 * {@code /metrics} route. The Metrics binding is primary, overriding the
 * container's {@code NoopMetrics} builtin — installing this module routes
 * every framework counter (event bus, HTTP engine) into {@code /metrics}.
 */
@Marker(Builtin.class)
public final class CloudObserveModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(Tracer.class)
            .to((Container container) -> new TracerDefault(container.get(Metrics.class)))
            .marker(Local.class);
        b.bind(MetricsDefault.class).to(MetricsDefault.class);
        // The SPI binding points at the concrete binding's instance, so
        // /metrics (which injects the concrete type) and every Metrics
        // consumer share one registry.
        b.bind(Metrics.class)
            .to((Container container) -> container.get(MetricsDefault.class))
            .primary();
        b.contribute(Route.class).add("metrics", Route.get("/metrics", MetricsHandler.class));
    }
}
