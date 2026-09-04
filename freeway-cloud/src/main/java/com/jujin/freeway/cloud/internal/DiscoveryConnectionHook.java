package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;

/**
 * Registry-client connection hook: runs BEFORE {@code freeway.http.server}.
 * The local in-process registry keeps no persistent connection, so this is a
 * no-op here. Also validates the backend {@code type} keys — in the hook,
 * not in a provider, because resolving the symbol chain mid-construction
 * would cycle through the symbol provider chain. External-backend lifecycle
 * is owned by the custom adapter's own hooks (bound primary).
 */
public final class DiscoveryConnectionHook implements RuntimeHook {

    @Override
    public void start(Container container) {
        BackendTypeGuard.warnIfExternal(
            container, ServiceDiscovery.class,
            CloudConfigKeys.DISCOVERY_TYPE, "discovery");
        BackendTypeGuard.warnIfExternal(
            container, ServiceRegistry.class,
            CloudConfigKeys.REGISTRY_TYPE, "registry");
    }
}
