package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry lifecycle hook: runs AFTER {@code freeway.http.server} so
 * {@code host:port} are known. Collects every {@link ServiceDeclaration}
 * contribution, registers each instance and starts the renew heartbeat; on
 * stop, halts the heartbeat and unregisters everything (traffic is removed
 * before the HTTP server shuts down — hooks stop in reverse order).
 */
public final class RegistryLifecycleHook implements RuntimeHook {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryLifecycleHook.class);
    private static final long RENEW_INTERVAL_SECONDS = 10;

    private final List<ServiceInstance> registered = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;

    @Override
    public void start(Container container) throws Exception {
        ServiceRegistry registry = container.get(ServiceRegistry.class);
        for (ServiceDeclaration declaration : container.extension(ServiceDeclaration.class).all()) {
            ServiceInstance instance = declaration.resolve(container);
            if (instance == null) {
                continue; // declaration not applicable this boot
            }
            registry.register(instance);
            registered.add(instance);
            LOG.info("Registered service '{}' instance '{}' at {}", instance.serviceId(),
                instance.instanceId(), instance.endpoint());
        }
        if (!registered.isEmpty()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cloud-registry-heartbeat");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                for (ServiceInstance instance : registered) {
                    try {
                        registry.renew(instance.serviceId(), instance.instanceId());
                    } catch (Exception ex) {
                        LOG.warn("Heartbeat renew failed for {} instance {}: {}",
                            instance.serviceId(), instance.instanceId(), ex.getMessage());
                    }
                }
            }, RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop(Container container) {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
        ServiceRegistry registry;
        try {
            registry = container.get(ServiceRegistry.class);
        } catch (Exception ex) {
            // The container is already dismantling around us: a missing
            // registry is not worth failing the shutdown over — the lease
            // expires on its own.
            LOG.warn("Deregistration skipped: {}", ex.toString());
            registered.clear();
            return;
        }
        for (ServiceInstance instance : registered) {
            try {
                registry.unregister(instance);
                LOG.info("Deregistered service '{}' instance '{}'", instance.serviceId(), instance.instanceId());
            } catch (Exception ex) {
                LOG.warn("Deregister failed for {} instance {}: {}",
                    instance.serviceId(), instance.instanceId(), ex.getMessage());
            }
        }
        registered.clear();
    }
}
