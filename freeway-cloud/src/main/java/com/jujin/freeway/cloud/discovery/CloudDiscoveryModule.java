package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.internal.ActiveBindingProbe;
import com.jujin.freeway.cloud.internal.DiscoveryConnectionHook;
import com.jujin.freeway.cloud.internal.HttpServiceDeclaration;
import com.jujin.freeway.cloud.internal.RegistryHealthContributor;
import com.jujin.freeway.cloud.internal.RegistryLifecycleHook;
import com.jujin.freeway.cloud.internal.RegistryStore;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for discovery: {@link ServiceRegistry} / {@link ServiceDiscovery}
 * (in-process registry store, {@code @Local} marker) and
 * {@link LoadBalancer} (round-robin default).
 *
 * <p>Lifecycle: {@link CloudHooks#DISCOVERY} (before the HTTP server —
 * registry client connection, no-op for the local backend) and
 * {@link CloudHooks#REGISTRY} (after the HTTP server — collects
 * {@link ServiceDeclaration} contributions and registers; stops first on
 * shutdown). The registry hook orders against {@link CloudHooks#HTTP_SERVER},
 * so the HTTP module must be installed.
 */
@Marker(Builtin.class)
public final class CloudDiscoveryModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(RegistryStore.class).to(RegistryStore.class);
        b.bind(ServiceRegistry.class)
            .to((Container container) -> new ServiceRegistryDefault(container.get(RegistryStore.class)))
            .marker(Local.class)
            ;
        b.bind(ServiceDiscovery.class)
            .to((Container container) -> new ServiceDiscoveryDefault(container.get(RegistryStore.class)))
            .marker(Local.class)
            ;
        b.bind(LoadBalancer.class)
            .to(LoadBalancerDefault.class)
            .marker(Local.class)
            ;
        // Lets container-created health contributions ask whether the @Local
        // defaults are still selected without injecting Container itself.
        b.bind(ActiveBindingProbe.class)
            .to((Container container) -> new ActiveBindingProbe(container))
            ;

        b.contribute(ServiceDeclaration.class).add("http", new HttpServiceDeclaration());
        // Readiness contributor for /health/ready — belongs here, not in the
        // health module: it probes the registry store, so standalone health
        // installs (without discovery) stay dependency-free. It deactivates
        // when an extension adapter replaces the local bindings.
        b.contribute(CloudHealthContributor.class).add(RegistryHealthContributor.class);
        b.contribute(RuntimeHook.class)
            .add(CloudHooks.DISCOVERY, new DiscoveryConnectionHook())
            .before(CloudHooks.HTTP_SERVER);
        b.contribute(RuntimeHook.class)
            .add(CloudHooks.REGISTRY, new RegistryLifecycleHook())
            .after(CloudHooks.HTTP_SERVER);
    }
}
