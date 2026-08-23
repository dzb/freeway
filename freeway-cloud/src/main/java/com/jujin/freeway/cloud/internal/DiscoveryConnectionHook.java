package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/**
 * Registry-client connection hook: runs BEFORE {@code freeway.http.server}.
 * The local in-process registry keeps no persistent connection, so this is a
 * no-op here; freeway-ext backends (Nacos/Consul/K8s) initialize and close
 * their registry clients in this hook. Also validates the backend
 * {@code type} keys — in the hook, not in a provider, because resolving
 * {@link SymbolSource} mid-construction would cycle through the symbol
 * provider chain.
 */
public final class DiscoveryConnectionHook implements RuntimeHook {

    @Override
    public void start(Container container) {
        SymbolSource symbols = container.get(SymbolSource.class);
        BackendTypeGuard.warnIfExternal(
            symbols, CloudConfigKeys.DISCOVERY_TYPE, "discovery");
        BackendTypeGuard.warnIfExternal(
            symbols, CloudConfigKeys.REGISTRY_TYPE, "registry");
    }

    @Override
    public void stop(Container container) {
        // Close registry client connections (ext backends).
    }
}
