package com.jujin.freeway.cloud.member;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.internal.BackendTypeGuard;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;

/**
 * Backend-type guard, run BEFORE {@code freeway.http.server}. Discovery and
 * service membership have no built-in backend, so this warns when a
 * {@code type} is configured but no implementation is bound at all.
 * The guard lives in a hook, not a provider, because resolving the symbol chain
 * mid-construction would cycle through the symbol provider chain.
 * External-backend lifecycle is owned by the custom adapter's own hooks.
 */
final class BackendGuardHook implements RuntimeHook {

    @Override
    public void start(Container container) {
        BackendTypeGuard.warnIfMissing(
            container, ServiceDiscovery.class,
            MemberModule.ConfigKeys.DISCOVERY_TYPE, "discovery");
        BackendTypeGuard.warnIfMissing(
            container, ServiceRegistry.class,
            MemberModule.ConfigKeys.REGISTRY_TYPE, "registry");
    }
}
