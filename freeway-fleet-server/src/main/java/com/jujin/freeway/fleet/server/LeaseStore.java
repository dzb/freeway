package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.time.Duration;
import java.util.List;

/**
 * The lease table behind a registry backend: who is registered, when they were
 * last seen, and whether they are ready.
 *
 * <p>One owner for the server's storage semantics. {@link MemLeaseStore} is
 * the current implementation; a durable PostgreSQL store can replace it without
 * changing the HTTP surface.</p>
 */
public interface LeaseStore {

    /** Staleness threshold for the in-memory implementation. */
    Duration DEFAULT_EVICTION = Duration.ofSeconds(30);

    void register(ServiceInstance instance);

    void register(ServiceInstance instance, ServiceHealth health);

    boolean renew(String serviceId, String instanceId, boolean ready);

    boolean markReady(String serviceId, String instanceId, boolean ready);

    boolean renew(String serviceId, String instanceId);

    void unregister(ServiceInstance instance);

    boolean unregister(String serviceId, String instanceId);

    int instanceCount();

    List<ServiceInstance> liveReadyInstances(String serviceId, Duration maxAge);
}
