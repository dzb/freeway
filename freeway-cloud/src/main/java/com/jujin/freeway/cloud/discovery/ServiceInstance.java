package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerImpl;

import java.util.Map;
import java.util.Objects;

/**
 * One discovered instance: logical identity + stable instance identity +
 * structured location + free-form metadata.
 *
 * <p>{@code serviceId} is the logical service name (plain string, same
 * namespace as container binding ids). {@code instanceId} is the stable
 * per-instance identity — a rescheduled container keeps its instanceId and
 * only updates its {@link Endpoint}. Health is deliberately NOT part of this
 * record (see {@link Health}).
 *
 * @param serviceId  logical service name
 * @param instanceId stable instance identity, decoupled from location
 * @param endpoint   structured locator
 * @param metadata   zone/version/weight/canary/... free-form bag
 */
public record ServiceInstance(
    String serviceId,
    String instanceId,
    Endpoint endpoint,
    Map<String, String> metadata
) {

    private static final Coercer COERCER = new CoercerImpl();

    public ServiceInstance {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ServiceInstance of(String serviceId, String instanceId, Endpoint endpoint) {
        return new ServiceInstance(serviceId, instanceId, endpoint, Map.of());
    }

    public static ServiceInstance of(String serviceId, String instanceId, Endpoint endpoint, Map<String, String> metadata) {
        return new ServiceInstance(serviceId, instanceId, endpoint, metadata);
    }

    /** Typed accessor with default — two-arg {@link Coercer} + null short-circuit (no core API change). */
    public int weight() {
        return intMeta("weight", 1);
    }

    public String zone() {
        return metadata.getOrDefault("zone", "");
    }

    public String version() {
        return metadata.getOrDefault("version", "");
    }

    public boolean isCanary() {
        return Boolean.parseBoolean(metadata.getOrDefault("canary", "false"));
    }

    private int intMeta(String key, int defaultValue) {
        String raw = metadata.get(key);
        return raw == null ? defaultValue : COERCER.coerce(raw, int.class);
    }
}
