package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;

/**
 * Registry-client connection hook: runs BEFORE {@code freeway.http.server}.
 * The local in-process registry keeps no persistent connection, so this is a
 * no-op here; freeway-ext backends (Nacos/Consul/K8s) initialize and close
 * their registry clients in this hook.
 */
public final class DiscoveryConnectionHook implements RuntimeHook {

    @Override
    public void start(Container container) {
        // No persistent connection in the local registry.
    }

    @Override
    public void stop(Container container) {
        // Close registry client connections (ext backends).
    }
}
