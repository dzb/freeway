package com.jujin.freeway.cloud.discovery;

import java.util.Optional;

/**
 * Optional registry capability: publish an instance as not-yet-ready in the
 * same write that registers it, then flip readiness once its dependencies are
 * healthy.
 *
 * <p>This is a separate interface, not {@code supportsReadiness()} plus two
 * default no-ops on {@link ServiceRegistry}: a backend either implements the
 * capability or it does not. The type is the capability, so a registry cannot
 * claim readiness support and silently leave the readiness calls inert.</p>
 */
public interface ReadinessAwareRegistry extends ServiceRegistry {

    /** The capability view exposed through {@link ServiceRegistry#readinessCapability()}. */
    @Override
    default Optional<ReadinessAwareRegistry> readinessCapability() {
        return Optional.of(this);
    }

    /**
     * Registers (or re-registers) an instance with the readiness state the
     * caller already knows. One write keeps "in the registry" and "not
     * discoverable yet" from being separated by a visible window.
     */
    void register(ServiceInstance instance, ServiceHealth health);

    /** Publishes this instance's current readiness. */
    void markReady(ServiceInstance instance, boolean ready);
}
