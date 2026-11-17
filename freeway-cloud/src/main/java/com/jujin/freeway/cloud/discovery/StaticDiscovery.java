package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed service discovery: a service id maps to a configured instance list.
 *
 * <p>This is the no-registry mode. A list with several instances lets the
 * client-side {@link LoadBalancer} choose per call; a list with one endpoint is
 * the VIP form, where an external load balancer owns instance selection. There
 * is no registration, heartbeat or eviction: static means the deployment owns
 * the membership.</p>
 *
 * <p>Prefer a VIP/DNS name over pod IPs: static lists do not follow
 * rescheduling. Programmatic instances retain their metadata, so weights and
 * zones work as they do with dynamic discovery; config-form entries are
 * endpoint-only and carry no metadata yet.</p>
 *
 * <p>Config form: {@code StaticDiscovery.of(SymbolSource)} reads
 * {@code freeway.cloud.discovery.static.services}. Each comma-separated entry is
 * {@code serviceId=scheme://host:port[/basePath]}; repeating a service id adds
 * another instance. The URI must be absolute and include an explicit port; the
 * instance id is the normalized endpoint URI. The configuration is read once and
 * has no registration, heartbeat, eviction or hot-reload behavior.</p>
 */
public final class StaticDiscovery implements ServiceDiscovery {

    private final Map<String, List<ServiceInstance>> services;

    private StaticDiscovery(Map<String, List<ServiceInstance>> services) {
        this.services = services;
    }

    /** Builds a static discovery from a service-id → instance-list map. */
    public static StaticDiscovery of(Map<String, List<ServiceInstance>> services) {
        Objects.requireNonNull(services, "services");
        Map<String, List<ServiceInstance>> copy = new LinkedHashMap<>();
        services.forEach((serviceId, instances) -> {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(instances, "instances");
            copy.put(serviceId, List.copyOf(instances));
        });
        return new StaticDiscovery(Map.copyOf(copy));
    }

    /** Single-service convenience; one instance is the VIP form. */
    public static StaticDiscovery of(String serviceId, ServiceInstance... instances) {
        Objects.requireNonNull(serviceId, "serviceId");
        return of(Map.of(serviceId, List.of(instances)));
    }

    /**
     * Reads a static service map from
     * {@code freeway.cloud.discovery.static.services}.
     *
     * <p>Each comma-separated entry is
     * {@code serviceId=scheme://host:port[/basePath]}. Repeating a service id
     * adds an instance; instance ids are the normalized endpoint URIs. Missing,
     * blank, malformed or duplicate entries fail immediately with the key name.</p>
     */
    public static StaticDiscovery of(SymbolSource symbols) {
        Objects.requireNonNull(symbols, "symbols");
        List<String> entries = symbols.resolve(
            SymbolSpec.list(StaticModule.ConfigKeys.SERVICES, List.of()));
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                StaticModule.ConfigKeys.SERVICES
                    + " must contain at least one serviceId=scheme://host:port[/basePath] entry");
        }

        Map<String, List<ServiceInstance>> services = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i).trim();
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw invalidEntry(i, "expected serviceId=scheme://host:port[/basePath]");
            }
            String serviceId = entry.substring(0, separator).trim();
            String address = entry.substring(separator + 1).trim();
            if (serviceId.isBlank() || serviceId.contains(",")) {
                throw invalidEntry(i, "serviceId must not be blank or contain a comma");
            }

            URI uri;
            try {
                uri = URI.create(address);
            } catch (IllegalArgumentException e) {
                throw invalidEntry(i, "invalid endpoint URI '" + address + "': " + e.getMessage());
            }
            if (uri.getScheme() == null || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidEntry(i,
                    "endpoint must be an absolute URI with scheme, host, explicit port "
                        + "and no userinfo, query or fragment: '" + address + "'");
            }
            ServiceEndpoint endpoint = ServiceEndpoint.of(
                uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
            String instanceId = endpoint.uri().toString();
            List<ServiceInstance> instances = services.computeIfAbsent(
                serviceId, ignored -> new ArrayList<>());
            if (instances.stream().anyMatch(instance -> instance.instanceId().equals(instanceId))) {
                throw invalidEntry(i,
                    "duplicate instance endpoint for service '" + serviceId + "': " + address);
            }
            instances.add(ServiceInstance.of(serviceId, instanceId, endpoint));
        }
        return of(services);
    }

    private static IllegalArgumentException invalidEntry(int index, String detail) {
        return new IllegalArgumentException(
            StaticModule.ConfigKeys.SERVICES + " entry " + (index + 1) + ": " + detail);
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        return services.getOrDefault(serviceId, List.of());
    }
}
