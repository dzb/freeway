package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.MetricsHandler;
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
 *
 * <p>The module owns one {@link MetricsDefault} registry and binds it under
 * all three of its roles: the concrete class (single instance), the
 * {@link Metrics} SPI (primary), and the {@link MetricsSnapshot} export view
 * used by the {@code /metrics} route. Bindings resolve through the container
 * as proxies limited to the requested interface, so the snapshot cannot be
 * derived from the Metrics service by {@code instanceof} — it is registered
 * directly instead, and always exports the same registry the framework
 * counters record into.
 *
 * <p><b>Replacing the registry.</b> The container accepts exactly one
 * {@code primary()} binding per type, so an external metrics backend cannot
 * add a second primary alongside this module (that is an
 * {@code AmbiguousBindingException} at first resolution). To use a backend,
 * install it <i>instead of</i> this module (CloudModule is the umbrella that
 * always installs it — use the cloud sub-modules without
 * CloudObserveModule for subset assembly). The backend binds
 * {@code Metrics.class} as {@code primary()} and supplies its own
 * {@code /metrics} route (and its own export view, e.g. a
 * {@link MetricsSnapshot} binding) — nothing in this module is auto-followed.
 */
@Marker(Builtin.class)
public final class CloudObserveModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(Tracer.class)
            .to((Container container) -> new TracerDefault(container.get(Metrics.class)))
            .marker(Local.class);
        // One module-owned registry, bound under every role: the concrete
        // singleton is the shared instance; the interface bindings delegate
        // to it, so counters recorded through the Metrics SPI and the text
        // exported by /metrics always observe the same registry.
        b.bind(MetricsDefault.class).to(MetricsDefault.class);
        b.bind(Metrics.class)
            .to((Container container) -> container.get(MetricsDefault.class))
            .primary();
        b.bind(MetricsSnapshot.class)
            .to((Container container) -> container.get(MetricsDefault.class));
        b.contribute(Route.class).add("metrics", Route.get("/metrics", MetricsHandler.class));
    }
}
