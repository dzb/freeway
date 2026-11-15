package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link LeaseStore} implementation for the Fleet Server. A durable
 * server store can replace it without changing the HTTP surface.
 */
public final class MemLeaseStore implements LeaseStore {

    private final Map<String, Map<String, Entry>> byService = new ConcurrentHashMap<>();

    private static final class Entry {
        private final ServiceInstance instance;
        private volatile ServiceHealth health;

        Entry(ServiceInstance instance, ServiceHealth health) {
            this.instance = instance;
            this.health = health;
        }

        void touch() {
            health = new ServiceHealth(health.live(), health.ready(), Instant.now());
        }

        /** Fresh heartbeat, with the readiness the caller just observed. */
        void markSeen(boolean ready) {
            health = new ServiceHealth(true, ready, Instant.now());
        }
    }

    /** Registers as live and ready — the shape every caller without a
     *  readiness signal uses. */
    @Override
    public void register(ServiceInstance instance) {
        register(instance, ServiceHealth.up());
    }

    /** Registers with the caller's probe state: a slow-starting instance is
     *  {@link ServiceHealth#starting()}, which discovery filters out until it flips. */
    @Override
    public void register(ServiceInstance instance, ServiceHealth health) {
        var instances = byService.computeIfAbsent(instance.serviceId(), k -> new ConcurrentHashMap<>());
        instances.put(instance.instanceId(), new Entry(instance, health));
    }

    /** @return false when this store no longer holds the instance (evicted or never seen) */
    /** Heartbeat that also asserts readiness — the readiness bit rides the
     *  heartbeat so a flipping instance costs no extra round trip. */
    @Override
    public boolean renew(String serviceId, String instanceId, boolean ready) {
        var instances = byService.get(serviceId);
        if (instances == null) {
            return false;
        }
        Entry entry = instances.get(instanceId);
        if (entry == null) {
            return false;
        }
        entry.markSeen(ready);
        return true;
    }

    /** Flips the readiness bit in place; keeps the heartbeat clock running.
     *  @return false when the store does not hold the instance */
    @Override
    public boolean markReady(String serviceId, String instanceId, boolean ready) {
        var instances = byService.get(serviceId);
        if (instances == null) {
            return false;
        }
        Entry entry = instances.get(instanceId);
        if (entry == null) {
            return false;
        }
        entry.markSeen(ready);
        return true;
    }

    @Override
    public boolean renew(String serviceId, String instanceId) {
        var instances = byService.get(serviceId);
        if (instances == null) {
            return false;
        }
        Entry entry = instances.get(instanceId);
        if (entry == null) {
            return false;
        }
        entry.touch();
        return true;
    }

    @Override
    public void unregister(ServiceInstance instance) {
        var instances = byService.get(instance.serviceId());
        if (instances == null) {
            return;
        }
        instances.remove(instance.instanceId());
        evictServiceMapIfEmpty(instance.serviceId());
    }

    /**
     * Removes by id, without the readiness filter {@link #liveReady} applies:
     * an instance that is still starting must be deregisterable, or a process
     * that fails before it is ready leaves an entry behind until eviction.
     *
     * @return false when the store does not hold the instance
     */
    @Override
    public boolean unregister(String serviceId, String instanceId) {
        var instances = byService.get(serviceId);
        if (instances == null || instances.remove(instanceId) == null) {
            return false;
        }
        evictServiceMapIfEmpty(serviceId);
        return true;
    }

    /** Total registered instances across all services (readiness reporting). */
    @Override
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
    @Override
    public List<ServiceInstance> liveReadyInstances(String serviceId, Duration maxAge) {
        var instances = byService.get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        List<ServiceInstance> result = new ArrayList<>();
        for (Map.Entry<String, Entry> me : instances.entrySet()) {
            Entry e = me.getValue();
            // One definition of the threshold, owned by ServiceHealth — this method
            // used to spell the same comparison out inline.
            if (e.health.isStale(maxAge)) {
                // Stale — evict so the map cannot grow without bound. Re-check
                // inside the bin lock: a heartbeat may have refreshed this very
                // Entry after the read above, which must not be evicted.
                instances.compute(me.getKey(), (key, current) ->
                    current == e && current.health.isStale(maxAge) ? null : current);
            } else if (e.health.live() && e.health.ready()) {
                result.add(e.instance);
            }
        }
        if (instances.isEmpty()) {
            evictServiceMapIfEmpty(serviceId);
        }
        return result;
    }

    /**
     * Drops the service's map when empty, atomically with respect to
     * {@link #register}'s {@code computeIfAbsent}: both lock the same bin, so
     * a registration racing the eviction either lands before the compute
     * (map no longer empty — kept) or after (fresh map — kept). A
     * check-then-remove pair here would delete a map that a concurrent
     * register had just repopulated, silently dropping the registration.
     */
    private void evictServiceMapIfEmpty(String serviceId) {
        byService.compute(serviceId, (k, v) -> (v == null || v.isEmpty()) ? null : v);
    }
}
