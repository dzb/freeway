package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.util.Map;

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

    @Override
    public ServiceInstance resolve(Container container) {
        WebServer server;
        try {
            server = container.get(WebServer.class);
        } catch (Exception e) {
            return null; // no HTTP module — nothing to register
        }
        SymbolSource symbols = container.get(SymbolSource.class);
        String serviceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_ID,
            symbols.resolve("freeway.app.name", "freeway-app"));
        String host = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_HOST, server.host());
        int port = Integer.parseInt(symbols.resolve(
            CloudConfigKeys.REGISTRY_SERVICE_PORT, String.valueOf(server.port())));
        String scheme = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_SCHEME, "http");
        String instanceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID,
            serviceId + "@" + host + ":" + port);
        return ServiceInstance.of(serviceId, instanceId,
            Endpoint.of(scheme, host, port), Map.of());
    }
}
