package com.jujin.freeway.fleet;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

/**
 * The in-process discovery backend: an in-memory lease store, local
 * {@code ServiceRegistry}/{@code ServiceDiscovery} defaults and the default
 * client-side load balancer.
 *
 * <p>Registration is not part of this module. Install
 * {@code RegistrationModule} from {@code freeway-cloud} when the application
 * should register its own endpoint, renew it and publish readiness. Keeping the
 * lifecycle out of the backend is what lets the same lifecycle drive the local,
 * Fleet and Consul backends.</p>
 */
@Marker(Builtin.class)
public final class LocalModule implements ModuleEx {

    private static final SymbolSpec<String> LOCAL_ZONE = SymbolSpec.of(
        ConfigKeys.DISCOVERY_LOCAL_ZONE, String.class, "");

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX));
        b.bind(LeaseStore.class).to(MemLeaseStore.class);
        b.bind(ServiceRegistry.class)
            .to((Container container) -> new LocalRegistrar(container.get(LeaseStore.class)))
            .marker(Local.class);
        b.bind(ServiceDiscovery.class)
            .to((Container container) -> new LocalDiscovery(container.get(LeaseStore.class)))
            .marker(Local.class);
        b.bind(LoadBalancer.class)
            .to((Container container) -> new LoadBalancerDefault(
                container.get(SymbolSource.class).resolve(LOCAL_ZONE)))
            .marker(Local.class);
    }

    /** Keys read by the local discovery backend. */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        public static final String DISCOVERY_LOCAL_ZONE = "freeway.cloud.discovery.local-zone";
    }
}
