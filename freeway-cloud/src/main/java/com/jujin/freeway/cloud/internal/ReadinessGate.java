package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.ioc.Container;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds a starting instance out of rotation until its dependencies say it can
 * serve: the instances are published as <em>not ready</em>, every contributed
 * {@link CloudHealthContributor} is re-asked until it passes, and only then does
 * the readiness bit flip.
 *
 * <p>It is a separate state machine on purpose — it has its own start, its own
 * end and its own retry, and the registry lifecycle hook would otherwise carry
 * two lifecycles in one class. The wait runs off the startup thread: a
 * dependency that is still warming must not block the boot. A failed check is
 * retried rather than reported as ready, and a throwing check counts as a
 * failure, not as a crash of the gate.</p>
 *
 * <p>An application with no contributors is ready the moment it is registered —
 * the behaviour before the gate existed, kept deliberately: the gate must not
 * make an ordinary fast-starting service wait for a signal that does not
 * exist. The same holds when the contributed checks already pass at boot: the
 * first evaluation is synchronous, and a healthy process is left exactly as it
 * registered. Marking it not-ready would open a window in which a ready
 * instance is undiscoverable — the cost this gate exists to prevent, paid by a
 * service that had nothing to wait for. That is why {@link #arm} answers
 * {@code null} in both cases instead of an inert gate.</p>
 */
final class ReadinessGate implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReadinessGate.class);

    /** How often a warming dependency is re-asked. */
    private static final Duration RETRY = Duration.ofSeconds(1);

    private final List<CloudHealthContributor> checks;
    private final ServiceRegistry registry;
    private final List<ServiceInstance> instances;
    private final Duration retry;
    private volatile Thread runner;
    /** Set by close(); the loop checks it so a gate closed before start() can
     *  never flip readiness afterwards. */
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReadinessGate(List<CloudHealthContributor> checks, ServiceRegistry registry,
                          List<ServiceInstance> instances, Duration retry) {
        this.checks = checks;
        this.registry = registry;
        this.instances = instances;
        this.retry = retry;
    }

    /**
     * Arms a gate for {@code instances}, publishing them as not-ready first.
     *
     * @return {@code null} when the application contributed no readiness checks:
     *     there is nothing to wait for, and a gate would only delay an
     *     instance that is in fact ready
     */
    static ReadinessGate arm(Container container, ServiceRegistry registry,
                             List<ServiceInstance> instances) {
        List<CloudHealthContributor> checks =
            container.extension(CloudHealthContributor.class).all();
        if (checks.isEmpty() || instances.isEmpty()) {
            return null;
        }
        ReadinessGate gate = new ReadinessGate(checks, registry, instances, RETRY);
        if (gate.allHealthy()) {
            // Everything is already healthy at boot: there is nothing to wait
            // for. The instance was registered as starting (the hook knows a
            // gate will arm before it registers), so the one write that makes
            // it discoverable belongs here — returning without it would leave
            // a perfectly ready process invisible until its next heartbeat.
            for (ServiceInstance instance : instances) {
                registry.markReady(instance, true);
            }
            return null;
        }
        for (ServiceInstance instance : instances) {
            registry.markReady(instance, false);
        }
        // The seam's markReady is a no-op by default, so an external backend
        // either implements it or ignores the gate entirely. Say which one it
        // is once, instead of leaving "the gate never takes effect" to be
        // discovered from behaviour.
        ActiveBindingProbe probe = optionalProbe(container);
        if (probe != null && !probe.isLocal(ServiceRegistry.class)) {
            LOG.info("Readiness gate armed, but the bound registry is not the built-in one — "
                + "readiness flips take effect only if that adapter implements "
                + "ServiceRegistry.markReady");
        }
        return gate;
    }

    /** Whether every contributor currently answers healthy. */
    boolean ready() {
        return allHealthy();
    }

    void start() {
        if (closed.get()) {
            return;
        }
        runner = Thread.ofVirtual().name("cloud-registry-readiness").start(this::await);
    }

    private void await() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            if (allHealthy()) {
                for (ServiceInstance instance : instances) {
                    registry.markReady(instance, true);
                }
                LOG.info("Readiness gate passed — {} instance(s) published as ready",
                    instances.size());
                return;
            }
            try {
                Thread.sleep(retry.toMillis());
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ActiveBindingProbe optionalProbe(Container container) {
        try {
            return container.get(ActiveBindingProbe.class);
        } catch (RuntimeException absent) {
            return null; // installed without the discovery module that binds it
        }
    }

    /** Every contributor must answer healthy; a throwing check is a failed one. */
    private boolean allHealthy() {
        for (CloudHealthContributor check : checks) {
            try {
                if (!check.check().healthy()) {
                    return false;
                }
            } catch (RuntimeException failed) {
                LOG.debug("Readiness check '{}' threw — treating as not ready: {}",
                    check.name(), failed.getMessage());
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        closed.set(true);
        Thread thread = runner;
        if (thread != null) {
            thread.interrupt();
            runner = null;
        }
    }
}
