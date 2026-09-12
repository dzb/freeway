package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * Built-in {@link ServiceDeclaration} for the HTTP endpoint: registers the
 * {@link WebServer}'s address under the configured service id (fallback
 * chain: {@code freeway.cloud.registry.service-id} →
 * {@code freeway.app.name} → {@code freeway-app}).
 *
 * <p>Returns {@code null} when no {@link WebServer} is bound (HTTP module not
 * installed) — the registry hook skips it. Scheme and host default to
 * {@code auto} ({@link ServiceIdentity}): the scheme follows the server's
 * transport, the host prefers {@code POD_IP} and then a routable local
 * address, so a container does not register a bind-all endpoint by omission.
 * The instance id defaults to a derived key and can be pinned via
 * {@code freeway.cloud.registry.service-instance-id}.
 */
public final class HttpServiceDeclaration implements ServiceDeclaration {

    private static final Logger LOG = LoggerFactory.getLogger(HttpServiceDeclaration.class);

    /** Malformed values fail with the key named; unset falls back to the live server port. */
    private static final SymbolSpec<Integer> SERVICE_PORT = SymbolSpec.of(
        CloudConfigKeys.REGISTRY_SERVICE_PORT, Integer.class, null, Integer::parseInt);

    /** Scheme/host defaults are the {@code auto} token; the derivation lives in
     *  {@link ServiceIdentity}, shared with the event mesh. */
    private static final SymbolSpec<String> SERVICE_SCHEME = SymbolSpec.of(
        CloudConfigKeys.REGISTRY_SERVICE_SCHEME, String.class,
        CloudConfigKeys.REGISTRY_SERVICE_SCHEME_DEFAULT, Function.identity());
    private static final SymbolSpec<String> SERVICE_HOST = SymbolSpec.of(
        CloudConfigKeys.REGISTRY_SERVICE_HOST, String.class,
        CloudConfigKeys.REGISTRY_SERVICE_HOST_DEFAULT, Function.identity());


    /**
     * The instance this node registers — and the identity it presents to the
     * rest of the system. Every consumer resolves it here: the registry through
     * {@link #resolve}, the event mesh through this factory, so a node cannot
     * present two names. Null when there is no HTTP module (nothing to
     * register).
     */
    @Override
    public ServiceInstance resolve(Container container) {
        return of(container);
    }

    /** @see #resolve */
    public static ServiceInstance of(Container container) {
        WebServer server;
        try {
            server = container.get(WebServer.class);
        } catch (MissingBindingException e) {
            return null; // no HTTP module — nothing to register
        }
        SymbolSource symbols = container.get(SymbolSource.class);
        String serviceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_ID,
            symbols.resolve("freeway.app.name", "freeway-app"));
        String scheme = ServiceIdentity.scheme(
            symbols.resolve(SERVICE_SCHEME), server.secure(), serviceId);
        String host = ServiceIdentity.host(
            symbols.resolve(SERVICE_HOST), server.host(), serviceId);
        // The default port is the live server's port, not a static value —
        // resolve raw and fall back manually.
        Integer configuredPort = symbols.resolve(SERVICE_PORT);
        int port = configuredPort != null ? configuredPort : server.port();
        String instanceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID,
            serviceId + "@" + host + ":" + port);
        if (ServiceIdentity.isBindAll(host)) {
            LOG.warn("Registering unroutable host '{}' for service '{}' — peers cannot call it;"
                    + " set {} to the address other nodes should use (e.g. a Pod IP)",
                host, serviceId, CloudConfigKeys.REGISTRY_SERVICE_HOST);
        }
        return ServiceInstance.of(serviceId, instanceId,
            Endpoint.of(scheme, host, port), Map.of());
    }
}
