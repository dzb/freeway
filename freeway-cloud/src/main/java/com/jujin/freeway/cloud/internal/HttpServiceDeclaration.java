package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Built-in {@link ServiceDeclaration} for the HTTP endpoint: registers the
 * {@link WebServer}'s address under the configured service id (defaults:
 * {@code freeway.cloud.registry.service-id} → {@code freeway.app.name}).
 *
 * <p>Returns {@code null} when no {@link WebServer} is bound (HTTP module not
 * installed) — the registry hook skips it. Host override via
 * {@code freeway.cloud.registry.service-host} covers 0.0.0.0 / Pod IP
 * injection; the instance id defaults to a derived key and can be pinned via
 * {@code freeway.cloud.registry.service-instance-id}.
 */
public final class HttpServiceDeclaration implements ServiceDeclaration {

    private static final Logger LOG = LoggerFactory.getLogger(HttpServiceDeclaration.class);

    /** Malformed values fail with the key named; unset falls back to the live server port. */
    private static final ConfigSpec<Integer> SERVICE_PORT = ConfigSpec.of(
        CloudConfigKeys.REGISTRY_SERVICE_PORT, Integer.class, null, Integer::parseInt);

    /** Bind-all addresses: reachable locally, unreachable from other nodes. */
    private static final Set<String> UNROUTABLE_HOSTS = Set.of("0.0.0.0", "::", "");

    @Override
    public ServiceInstance resolve(Container container) {
        WebServer server;
        try {
            server = container.get(WebServer.class);
        } catch (MissingBindingException e) {
            return null; // no HTTP module — nothing to register
        }
        SymbolSource symbols = container.get(SymbolSource.class);
        String serviceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_ID,
            symbols.resolve("freeway.app.name", "freeway-app"));
        String host = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_HOST, server.host());
        // The default port is the live server's port, not a static value —
        // resolve raw and fall back manually.
        Integer configuredPort = symbols.resolve(SERVICE_PORT);
        int port = configuredPort != null ? configuredPort : server.port();
        String scheme = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_SCHEME, "http");
        String instanceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID,
            serviceId + "@" + host + ":" + port);
        if (UNROUTABLE_HOSTS.contains(host)) {
            LOG.warn("Registering unroutable host '{}' for service '{}' — peers cannot call it;"
                    + " set {} to the address other nodes should use (e.g. a Pod IP)",
                host, serviceId, CloudConfigKeys.REGISTRY_SERVICE_HOST);
        }
        return ServiceInstance.of(serviceId, instanceId,
            Endpoint.of(scheme, host, port), Map.of());
    }
}
