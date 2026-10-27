package com.jujin.freeway.cloud.event;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

/**
 * Feeds the mesh's discovered peer set from the bound {@link ServiceDiscovery}:
 * on every tick it asks for the instances of this node's own service and hands
 * the whole answer — this node excluded — to
 * {@code CloudEventBus#replaceDiscoveredPeers}.
 *
 * <p>The bus's snapshot semantics are what make a poll loop safe: an instance
 * that left discovery stops being dialed on the next tick, and a tick that
 * fails (a discovery outage) changes nothing — the mesh keeps its previous set
 * rather than reading one failed lookup as "every peer left". Static
 * {@code event.peers} are untouched: the two sources are unioned on the
 * connector side, so an empty answer here never cancels a static list.</p>
 *
 * <p>A peer is the {@code host:port} of the registered endpoint; the dial
 * scheme stays the connector's, which derives it from this node's own
 * identity — a node cannot be fed an address it would dial on the wrong
 * scheme.</p>
 */
final class DiscoveryPeerFeeder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryPeerFeeder.class);

    private final ServiceDiscovery discovery;
    private final String serviceId;
    private final String selfInstanceId;
    private final Consumer<List<String>> sink;
    private final long intervalMillis;
    private volatile ScheduledExecutorService scheduler;

    DiscoveryPeerFeeder(ServiceDiscovery discovery, String serviceId, String selfInstanceId,
                        Consumer<List<String>> sink, long intervalMillis) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.selfInstanceId = Objects.requireNonNull(selfInstanceId, "selfInstanceId");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive: " + intervalMillis);
        }
        this.intervalMillis = intervalMillis;
    }

    /** Feeds once, immediately, then on every tick until {@link #close()}. */
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("cloud-event-peer-feed-", 0).factory());
        scheduler.scheduleWithFixedDelay(this::feedOnce, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** One poll: discovery's answer, this node excluded, as one whole snapshot. */
    void feedOnce() {
        List<ServiceInstance> found;
        try {
            found = discovery.instances(serviceId);
        } catch (RuntimeException e) {
            LOG.warn("Mesh peer feed skipped — discovery lookup for '{}' failed: {}",
                serviceId, String.valueOf(e.getMessage()));
            return;
        }
        Set<String> peers = new LinkedHashSet<>();
        for (ServiceInstance instance : found) {
            if (selfInstanceId.equals(instance.instanceId())) {
                continue; // dialing ourselves would only be closed at the hello
            }
            peers.add(instance.endpoint().host() + ":" + instance.endpoint().port());
        }
        sink.accept(List.copyOf(peers));
        LOG.debug("Mesh peer feed: service '{}' → {} discovered peer(s)", serviceId, peers.size());
    }

    @Override
    public void close() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }
}
