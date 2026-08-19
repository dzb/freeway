package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.MeterRegistryDefault;
import com.jujin.freeway.cloud.internal.MetricsHandler;
import com.jujin.freeway.cloud.internal.TracerDefault;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for observability: {@link Tracer} → {@link TracerDefault}
 * (ScopedValue context + MDC display layer), {@link MeterRegistry} →
 * {@link MeterRegistryDefault} (in-memory) with a Prometheus-text
 * {@code /metrics} route.
 */
@Marker(Builtin.class)
public final class CloudObserveModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(Tracer.class).to(TracerDefault.class).marker(Local.class).primary();
        b.bind(MeterRegistry.class).to(MeterRegistryDefault.class).marker(Local.class).primary();
        b.contribute(Route.class).add("metrics", Route.get("/metrics", MetricsHandler.class));
    }
}
