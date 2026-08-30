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
 * The route serves the primary registry through its
 * {@link MetricsSnapshot} view.
 */
@Marker(Builtin.class)
public final class CloudObserveModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(Tracer.class)
            .to((Container container) -> new TracerDefault(container.get(Metrics.class)))
            .marker(Local.class);
        b.bind(Metrics.class).to(MetricsDefault.class).primary();
        // /metrics exports the primary registry's snapshot view — a
        // backend that replaces Metrics and renders itself is followed
        // automatically; one that cannot fails startup with a named error
        // instead of a bare ClassCastException.
        b.bind(MetricsSnapshot.class)
            .to((Container container) -> {
                Metrics metrics = container.get(Metrics.class);
                if (metrics instanceof MetricsSnapshot snapshot) {
                    return snapshot;
                }
                throw new IllegalStateException("Metrics implementation "
                    + metrics.getClass().getName() + " does not implement "
                    + MetricsSnapshot.class.getName() + " — /metrics has no export view");
            });
        b.contribute(Route.class).add("metrics", Route.get("/metrics", MetricsHandler.class));
    }
}
