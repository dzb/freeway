package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import java.util.List;
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
    /** Publish readiness: POST …/instances/{instanceId}/ready (body or
     *  {@code ?ready=}), so a warming instance becomes discoverable at the
     *  moment it is ready rather than at the next heartbeat. */
    public static final String READY =
        "/registry/services/{serviceId}/instances/{instanceId}/ready";
    /** Header a writer must present when {@code freeway.cloud.registry.token}
     *  is set; the fleet client ({@code RegistryFleetModule}) sends the same header. */
    public static final String TOKEN_HEADER = "X-Registry-Token";
    /** Wire protocol of this surface. A server must keep accepting older
     *  protocols; a client refuses an unannounced one instead of guessing. */
    public static final int PROTO = 1;
    /** Client-declared protocol; echoed on every response, and an unsupported
     *  value is refused with {@code 426 Upgrade Required} rather than a 404 the
     *  caller would have to decode. */
    public static final String PROTO_HEADER = "X-Registry-Proto";
    /** Self-description: protocol, routes and auth posture. Deliberately open
     *  (no token): a client must be able to learn what it is talking to before
     *  it has credentials. */
    public static final String INDEX = "/registry";

    /**
     * The surface as data: one row per route, read by {@link #bind} to
     * contribute the routes and by {@link RegistryIndexHandler} to describe
     * them. There used to be two lists — this one and the index's — and the
     * index drifted (it was missing {@code ready} and itself). A single table
     * makes "what do we serve" answer itself.
     */
    static final List<Surface> SURFACE = List.of(
        new Surface("register", "freeway.cloud.registry.register", "POST", REGISTER, RegistryApi.class),
        new Surface("renew", "freeway.cloud.registry.renew", "POST", RENEW, RegistryApi.class),
        new Surface("unregister", "freeway.cloud.registry.unregister", "DELETE", UNREGISTER, RegistryApi.class),
        new Surface("discover", "freeway.cloud.registry.discover", "GET", DISCOVER, RegistryApi.class),
        new Surface("ready", "freeway.cloud.registry.ready", "POST", READY, RegistryApi.class),
        new Surface("index", "freeway.cloud.registry.index", "GET", INDEX, RegistryIndexHandler.class)
    );

    /** One served route: the index label, the contribution id, and how to route it. */
    record Surface(String label, String id, String method, String path,
                    Class<? extends RouteHandler> handler) {}

    @Override
    public void bind(Binder b) {
        for (Surface surface : SURFACE) {
            b.contribute(Route.class).add(surface.id(), route(surface));
        }
    }

    private static Route route(Surface surface) {
        return switch (surface.method()) {
            case "POST" -> Route.post(surface.path(), surface.handler());
            case "DELETE" -> Route.delete(surface.path(), surface.handler());
            default -> Route.get(surface.path(), surface.handler());
        };
    }
}
