package com.jujin.freeway.cloud.registration;

import com.jujin.freeway.cloud.internal.BackendTypeGuard;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;

/**
 * Backend-type guard, run BEFORE {@code freeway.http.server}. Discovery and
 * registration have no built-in local implementation, so this warns when an
 * external {@code type} is configured but no implementation is bound at all.
 * The guard lives in a hook, not a provider, because resolving the symbol chain
 * mid-construction would cycle through the symbol provider chain.
 * External-backend lifecycle is owned by the custom adapter's own hooks.
 */
final class TypeGuardHook implements RuntimeHook {

    @Override
    public void start(Container container) {
        BackendTypeGuard.warnIfMissing(
            container, ServiceDiscovery.class,
            RegistrationModule.ConfigKeys.DISCOVERY_TYPE, "discovery");
        BackendTypeGuard.warnIfMissing(
            container, ServiceRegistry.class,
            RegistrationModule.ConfigKeys.REGISTRY_TYPE, "registry");
    }
}
