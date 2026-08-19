package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.Container;

/**
 * Extension point: a module declares what endpoint(s) it wants registered
 * this boot. The discovery module collects all {@code ServiceDeclaration}
 * contributions and registers them in one place.
 *
 * <p>Invoked AFTER the HTTP server starts, so {@code host:port} are known
 * ({@code WebServer.host()/port()} require a started server).
 */
@FunctionalInterface
public interface ServiceDeclaration {

    /** Builds the instance to register for this boot. */
    ServiceInstance resolve(Container container);
}
