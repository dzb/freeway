package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.Health;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process registry store shared by {@link ServiceRegistryDefault} and
 * {@link ServiceDiscoveryDefault}. One instance per container, bound as a
 * singleton by {@code CloudDiscoveryModule}.
 */
public final class RegistryStore {

    /** Staleness threshold: instances without a heartbeat beyond this are evicted lazily. */
    public static final Duration DEFAULT_EVICTION = Duration.ofSeconds(30);

    private final Map<String, Map<String, Entry>> byService = new ConcurrentHashMap<>();

    private static final class Entry {
        private final ServiceInstance instance;
        private volatile Health health;

        Entry(ServiceInstance instance, Health health) {
            this.instance = instance;
            this.health = health;
        }

        void touch() {
            health = new Health(health.live(), health.ready(), Instant.now());
        }
    }

    public void register(ServiceInstance instance) {
        var instances = byService.computeIfAbsent(instance.serviceId(), k -> new ConcurrentHashMap<>());
        instances.put(instance.instanceId(), new Entry(instance, Health.up()));
    }

    public void renew(String serviceId, String instanceId) {
        var instances = byService.get(serviceId);
        if (instances == null) {
            return;
        }
        Entry entry = instances.get(instanceId);
        if (entry != null) {
            entry.touch();
        }
    }

    public void unregister(ServiceInstance instance) {
        var instances = byService.get(instance.serviceId());
        if (instances == null) {
            return;
        }
        instances.remove(instance.instanceId());
        if (instances.isEmpty()) {
            byService.remove(instance.serviceId(), instances);
        }
    }

    /** Total registered instances across all services (readiness reporting). */
    public int instanceCount() {
        int count = 0;
        for (Map<String, Entry> instances : byService.values()) {
            count += instances.size();
        }
        return count;
    }

    /**
     * Live && ready instances that have not gone stale. Stale entries are
     * evicted (removed) on read, not merely filtered out — the store's size
     * stays bounded and a dead instance cannot linger indefinitely.
     */
    public List<ServiceInstance> liveReady(String serviceId, Duration maxAge) {
        var instances = byService.get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        Instant cutoff = Instant.now().minus(maxAge);
        List<ServiceInstance> result = new ArrayList<>();
        for (Map.Entry<String, Entry> me : instances.entrySet()) {
            Entry e = me.getValue();
            if (e.health.lastSeen().isBefore(cutoff)) {
                // Stale — evict so the map cannot grow without bound.
                instances.remove(me.getKey(), e);
            } else if (e.health.live() && e.health.ready()) {
                result.add(e.instance);
            }
        }
        if (instances.isEmpty()) {
            byService.remove(serviceId, instances);
        }
        return result;
    }
}
