package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.MeterRegistryDefault;
import com.jujin.freeway.cloud.internal.MetricsHandler;
import com.jujin.freeway.cloud.internal.TracerDefault;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for observability: {@link Tracer} → {@link TracerDefault}
 * (ScopedValue context + MDC display layer + span duration into
 * {@code tracer.span.duration}), {@link MeterRegistry} →
 * {@link MeterRegistryDefault} (in-memory) with a Prometheus-text
 * {@code /metrics} route.
 */
@Marker(Builtin.class)
public final class CloudObserveModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(Tracer.class)
            .to((Container container) -> new TracerDefault(container.get(MeterRegistry.class)))
            .marker(Local.class);
        b.bind(MeterRegistry.class).to(MeterRegistryDefault.class).marker(Local.class);
        b.contribute(Route.class).add("metrics", Route.get("/metrics", MetricsHandler.class));
    }
}
