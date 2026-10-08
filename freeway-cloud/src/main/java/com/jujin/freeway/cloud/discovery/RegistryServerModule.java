package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

/**
 * Publishes the process-local registry as a small HTTP surface, so a single
 * freeway process can stand in as the mesh's registry during development and
 * lightweight deployment. It is deliberately not part of {@code CloudModule}:
 * an in-process RegistryStore is one thing, an HTTP-exposed registry server is another,
 * and only the application that wants to govern the mesh needs the listener.
 *
 * <p>Designed for a standalone registry: the same process that serves these
 * routes uses {@link RegistryStore} elsewhere too, but the useful path is
 * other processes writing into it through this surface. This module honors the
 * client side already bound by {@link com.jujin.freeway.cloud.discovery.CloudDiscoveryModule}:
 * its own {@link ServiceRegistry}/{@link ServiceDiscovery} defaults see
 * exactly what these routes write or dig up.
 *
 * <p>No auth, no TLS, best-effort semantics — it is a development helper and
 * deliberately honors the same at-most-once contract as the in-process store.
 * Production should move to {@code CloudDiscoveryModule}'s registry adapters
 * (Nacos/Kubernetes/etc.), which answer their own eviction windows.
 */
public final class RegistryServerModule implements ModuleEx {

    /** Register a ServiceInstance: POST a ServiceInstance JSON to …/instances. */
    public static final String REGISTER = "/registry/services/{serviceId}/instances";
    /** Heartbeat for one instance: POST …/instances/{instanceId}/renew. */
    public static final String RENEW = "/registry/services/{serviceId}/instances/{instanceId}/renew";
    /** Deregister: DELETE …/instances/{instanceId}. */
    public static final String UNREGISTER = "/registry/services/{serviceId}/instances/{instanceId}";
    /** List live instances: GET …/instances. */
    public static final String DISCOVER = "/registry/services/{serviceId}/instances";

    @Override
    public void bind(Binder b) {
        b.contribute(Route.class).add("freeway.cloud.registry.register",
            Route.post(REGISTER, RegistryApi.class));
        b.contribute(Route.class).add("freeway.cloud.registry.renew",
            Route.post(RENEW, RegistryApi.class));
        b.contribute(Route.class).add("freeway.cloud.registry.unregister",
            Route.delete(UNREGISTER, RegistryApi.class));
        b.contribute(Route.class).add("freeway.cloud.registry.discover",
            Route.get(DISCOVER, RegistryApi.class));
    }
}
