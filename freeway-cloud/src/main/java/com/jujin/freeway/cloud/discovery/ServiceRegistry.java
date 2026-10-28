package com.jujin.freeway.cloud.discovery;

import java.time.Duration;

/**
 * Lifecycle side of the registry: register / renew (heartbeat) / unregister.
 * Driven by the discovery module's {@code RuntimeHook}s (register on start,
 * unregister on stop, periodic renew in between).
 */
public interface ServiceRegistry {

    /** Registers (or re-registers) an instance, resetting its {@link Health#lastSeen()}. */
    void register(ServiceInstance instance);

    /**
     * Registers an instance with the probe state the caller already knows.
     *
     * <p>This exists so a slow-starting process is registered as
     * {@link Health#starting()}: "in the registry, not in the discovery result"
     * has to be one write, not a register followed by a mark-not-ready that
     * leaves a window where discovery can see it. The default delegates to
     * {@link #register(ServiceInstance)} for adapters whose backend cannot
     * express readiness at registration time — the same posture
     * {@link #markReady} takes.</p>
     */
    default void register(ServiceInstance instance, Health health) {
        register(instance);
    }

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

    /**
     * Publishes this instance's readiness, so a slow-starting process is not
     * discoverable before it can serve. Discovery only returns live
     * <em>and ready</em> instances ({@link ServiceDiscovery#instances}), so an
     * implementation that cannot express readiness leaves the seam promising
     * more than it delivers — the reason this method exists at all.
     *
     * <p>The default is a no-op for adapters whose backend owns readiness
     * itself (Consul's TTL/health checks answer it), and for any adapter not
     * yet migrated. Backends without that concept — the in-process store and
     * the registry fleet client (`RegistryFleetModule`) — implement it.</p>
     *
     * @param ready {@code false} marks the instance as starting/warming
     */
    default void markReady(ServiceInstance instance, boolean ready) { }

    /**
     * How long this registry needs after an {@link #unregister unregister} for
     * the removal to reach the consumers that route to the instance — the
     * window {@code freeway.cloud.registry.shutdown-drain=auto} waits out
     * before the socket closes.
     *
     * <p>The default is zero, which is right for the built-in in-process
     * registry: an endpoint disappears the moment it is unregistered, so there
     * is no propagation to wait for. An adapter backed by a real registry
     * answers its own window (a Nacos or Kubernetes-endpoints backend typically
     * needs a few seconds), which is knowledge the adapter has and the
     * deployment should not have to repeat per environment.</p>
     */
    default Duration drainWindow() {
        return Duration.ZERO;
    }
}
