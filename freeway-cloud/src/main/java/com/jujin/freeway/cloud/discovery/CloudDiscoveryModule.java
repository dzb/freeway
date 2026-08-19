package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.annotation.RoundRobin;
import com.jujin.freeway.cloud.internal.DiscoveryConnectionHook;
import com.jujin.freeway.cloud.internal.HttpServiceDeclaration;
import com.jujin.freeway.cloud.internal.LoadBalancerDefault;
import com.jujin.freeway.cloud.internal.RegistryLifecycleHook;
import com.jujin.freeway.cloud.internal.RegistryStore;
import com.jujin.freeway.cloud.internal.ServiceDiscoveryDefault;
import com.jujin.freeway.cloud.internal.ServiceRegistryDefault;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for discovery: {@link ServiceRegistry} / {@link ServiceDiscovery}
 * (in-process registry store, {@code @Local} + {@code .primary()}) and
 * {@link LoadBalancer} (round-robin default).
 *
 * <p>Lifecycle: {@code freeway.cloud.discovery} (before the HTTP server —
 * registry client connection, no-op for the local backend) and
 * {@code freeway.cloud.registry} (after the HTTP server — collects
 * {@link ServiceDeclaration} contributions and registers; stops first on
 * shutdown). The registry hook orders against {@code freeway.http.server},
 * so the HTTP module must be installed.
 */
@Marker(Builtin.class)
public final class CloudDiscoveryModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(RegistryStore.class).to(RegistryStore.class).scope(Scope.SINGLETON);
        b.bind(ServiceRegistry.class)
            .to((Container container) -> new ServiceRegistryDefault(container.get(RegistryStore.class)))
            .marker(Local.class)
            .primary();
        b.bind(ServiceDiscovery.class)
            .to((Container container) -> new ServiceDiscoveryDefault(container.get(RegistryStore.class)))
            .marker(Local.class)
            .primary();
        b.bind(LoadBalancer.class)
            .to(LoadBalancerDefault.class)
            .marker(RoundRobin.class)
            .primary();

        b.contribute(ServiceDeclaration.class).add("http", new HttpServiceDeclaration());
        b.contribute(RuntimeHook.class)
            .add("freeway.cloud.discovery", new DiscoveryConnectionHook())
            .before("freeway.http.server");
        b.contribute(RuntimeHook.class)
            .add("freeway.cloud.registry", new RegistryLifecycleHook())
            .after("freeway.http.server");
    }
}
