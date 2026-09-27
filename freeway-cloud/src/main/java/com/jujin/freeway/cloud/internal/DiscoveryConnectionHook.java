package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.cloud.CloudModule;

/**
 * Registry-client connection hook: runs BEFORE {@code freeway.http.server}.
 * The local in-process registry keeps no persistent connection, so this is a
 * no-op here. Also runs the backend-type guard (warns when an external
 * backend {@code type} is configured but no adapter is bound primary) — in
 * the hook, not in a provider, because resolving the symbol chain
 * mid-construction would cycle through the symbol provider chain.
 * External-backend lifecycle is owned by the custom adapter's own hooks
 * (bound primary).
 */
public final class DiscoveryConnectionHook implements RuntimeHook {

    @Override
    public void start(Container container) {
        BackendTypeGuard.warnIfExternal(
            container, ServiceDiscovery.class,
            ConfigKeys.DISCOVERY_TYPE, "discovery");
        BackendTypeGuard.warnIfExternal(
            container, ServiceRegistry.class,
            ConfigKeys.REGISTRY_TYPE, "registry");
    }
}
