package com.jujin.freeway.cloud.discovery;

/**
 * Lifecycle side of the registry: register / renew (heartbeat) / deregister.
 * Driven by the discovery module's {@code RuntimeHook}s (register on start,
 * deregister on stop, periodic renew in between).
 */
public interface ServiceRegistry {

    /** Registers (or re-registers) an instance, resetting its {@link Health#lastSeen()}. */
    void register(ServiceInstance instance);

    /** Heartbeat: refreshes the instance's {@code lastSeen} so it is not evicted as stale. */
    void renew(String serviceId, String instanceId);

    /** Removes the instance from the registry. */
    void deregister(ServiceInstance instance);
}
