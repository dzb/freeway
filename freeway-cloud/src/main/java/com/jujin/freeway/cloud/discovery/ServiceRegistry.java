package com.jujin.freeway.cloud.discovery;

/**
 * Lifecycle side of the registry: register / renew (heartbeat) / unregister.
 * Driven by the discovery module's {@code RuntimeHook}s (register on start,
 * unregister on stop, periodic renew in between).
 */
public interface ServiceRegistry {

    /** Registers (or re-registers) an instance, resetting its {@link Health#lastSeen()}. */
    void register(ServiceInstance instance);

    /**
     * Heartbeat: refreshes the instance's {@code lastSeen} so it is not evicted
     * as stale.
     *
     * @return {@code false} when the registry no longer knows this instance —
     *         it was evicted, its lease expired, or the backend restarted. The
     *         caller is expected to {@link #register(ServiceInstance) register}
     *         it again; a registry that answers {@code true} for an instance it
     *         does not hold leaves the caller believing it is reachable.
     */
    boolean renew(String serviceId, String instanceId);

    /** Removes the instance from the registry. */
    void unregister(ServiceInstance instance);
}
