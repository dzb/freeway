package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test fixture: an in-memory registration/discovery pair for tests that need a
 * controlled backend without a production registry or the removed local
 * module. It implements exactly the registration/readiness behaviour the
 * tests assert; it has no TTL, heartbeat scheduler, or drain policy.
 */
public final class TestDiscoveryModule implements ModuleEx {

    private final TestBackend backend = new TestBackend();

    @Override
    public void bind(Binder b) {
        b.bind(TestBackend.class).to(container -> backend);
        b.bind(ServiceRegistry.class).to(container -> backend).marker(Local.class);
        b.bind(ServiceDiscovery.class).to(container -> backend).marker(Local.class);
    }

    /** Direct fixture handle, for tests that assert the table itself. */
    public TestBackend backend() {
        return backend;
    }

    public static final class TestBackend implements ReadinessAwareRegistry, ServiceDiscovery {

        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public void register(ServiceInstance instance) {
            register(instance, ServiceHealth.up());
        }

        @Override
        public void register(ServiceInstance instance, ServiceHealth health) {
            Objects.requireNonNull(instance, "instance");
            entries.put(instance.instanceId(), new Entry(instance, health));
        }

        @Override
        public boolean renew(String serviceId, String instanceId) {
            Entry entry = entries.get(instanceId);
            return entry != null && entry.instance.serviceId().equals(serviceId);
        }

        @Override
        public void unregister(ServiceInstance instance) {
            entries.remove(instance.instanceId());
        }

        @Override
        public void markReady(ServiceInstance instance, boolean ready) {
            Entry entry = entries.get(instance.instanceId());
            if (entry != null) {
                entry.health = new ServiceHealth(true, ready, Instant.now());
            }
        }

        @Override
        public List<ServiceInstance> instances(String serviceId) {
            return entries.values().stream()
                .filter(entry -> entry.instance.serviceId().equals(serviceId))
                .filter(entry -> entry.health.live() && entry.health.ready())
                .map(entry -> entry.instance)
                .toList();
        }

        private static final class Entry {
            private final ServiceInstance instance;
            private volatile ServiceHealth health;

            private Entry(ServiceInstance instance, ServiceHealth health) {
                this.instance = instance;
                this.health = health;
            }
        }
    }
}
