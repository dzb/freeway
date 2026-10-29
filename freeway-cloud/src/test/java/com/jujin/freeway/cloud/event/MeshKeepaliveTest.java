package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The probe's own contract, without sockets: a tick pings, an unanswered probe
 * outside its budget means the peer stopped reading, a late pong inside the
 * budget does not, and the timeout callback fires at most once (the owner's
 * teardown must not run twice).
 *
 * <p>Liveness across a real socket — a half-open link being closed while a
 * healthy one survives — is pinned by {@link MeshKeepaliveEndToEndTest}.</p>
 */
class MeshKeepaliveTest {

    private ScheduledExecutorService scheduler;

    private MeshKeepalive probe(MeshKeepalive.Pinger pinger, Runnable onTimeout) {
        return probe(pinger, onTimeout, 30, 30);
    }

    private MeshKeepalive probe(MeshKeepalive.Pinger pinger, Runnable onTimeout,
                                long intervalMillis, long timeoutMillis) {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        return new MeshKeepalive(scheduler, pinger, onTimeout,
            intervalMillis, timeoutMillis, "fw-keepalive");
    }

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void an_unanswered_probe_declares_the_peer_dead() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger timeouts = new AtomicInteger();
        AtomicInteger pings = new AtomicInteger();
        try (MeshKeepalive keepalive = probe(
                payload -> { pings.incrementAndGet(); return true; },
                () -> { timeouts.incrementAndGet(); fired.countDown(); })) {
            keepalive.start();
            assertTrue(fired.await(2, TimeUnit.SECONDS),
                "a peer that never pongs must be declared dead");
            Thread.sleep(150); // several ticks would follow; none may fire again
            assertEquals(1, timeouts.get(),
                "the timeout must fire once, not on every later tick");
            assertTrue(pings.get() >= 1, "the probe pinged before deciding");
        }
    }

    @Test
    void the_budget_is_three_intervals_with_a_one_second_floor() {
        assertEquals(1_000, MeshKeepalive.timeoutFor(50),
            "a fast probe still gets a pause-sized budget");
        assertEquals(6_000, MeshKeepalive.timeoutFor(2_000),
            "a slow probe gets three intervals, so one late tick is not death");
    }

    @Test
    void a_pong_later_than_the_interval_is_not_a_dead_peer() throws Exception {
        AtomicReference<MeshKeepalive> self = new AtomicReference<>();
        AtomicInteger pings = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        try (MeshKeepalive keepalive = probe(
                payload -> {
                    pings.incrementAndGet();
                    // A pong that takes longer than the probe interval but stays
                    // inside the budget: a loaded JVM does this routinely.
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        MeshKeepalive current = self.get();
                        if (current != null) {
                            current.pongReceived();
                        }
                    });
                    return true;
                },
                timeouts::incrementAndGet,
                20, 300)) {
            self.set(keepalive);
            keepalive.start();
            Thread.sleep(400);
            assertEquals(0, timeouts.get(),
                "a late but answered probe must not be declared dead");
            assertTrue(pings.get() >= 1, "the probe kept probing");
        }
    }

    @Test
    void an_answered_probe_keeps_the_connection_alive() throws Exception {
        AtomicReference<MeshKeepalive> self = new AtomicReference<>();
        AtomicInteger pings = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        try (MeshKeepalive keepalive = probe(
                payload -> {
                    pings.incrementAndGet();
                    MeshKeepalive current = self.get();
                    if (current != null) {
                        current.pongReceived(); // the peer answered instantly
                    }
                    return true;
                },
                timeouts::incrementAndGet)) {
            self.set(keepalive);
            keepalive.start();
            Thread.sleep(300); // ~10 ticks, each answered
            assertEquals(0, timeouts.get(),
                "a peer that answers every probe must never be closed");
            assertTrue(pings.get() >= 3, "the probe kept probing, got " + pings.get());
        }
    }

    @Test
    void a_refused_ping_is_a_dead_peer() throws Exception {
        CountDownLatch timedOut = new CountDownLatch(1);
        try (MeshKeepalive keepalive = probe(payload -> false, timedOut::countDown)) {
            keepalive.start();
            assertTrue(timedOut.await(2, TimeUnit.SECONDS),
                "a transport that cannot even send the probe is a dead link");
        }
    }
}
