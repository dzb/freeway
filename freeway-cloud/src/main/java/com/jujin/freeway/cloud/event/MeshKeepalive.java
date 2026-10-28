package com.jujin.freeway.cloud.event;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Half-open detection for one mesh connection, run by the connection owners
 * (the server's session handler and the connector's session handler) rather
 * than by the transport: the WS engine already answers pings and now reports
 * pongs, but how long a missing pong means "dead" — and what to do about it —
 * is the mesh's policy, because the mesh is what must unregister and re-dial.
 *
 * <p>Each tick sends a {@link java.net.http.WebSocket}-level ping (via the
 * caller's sender) and arms a budget; a pong arriving within it clears the
 * budget. A tick that finds the previous budget still armed means the peer
 * stopped reading without closing the TCP connection — the case a NAT or load
 * balancer produces silently — and the caller closes the connection, leaving
 * the existing reconnect/unregister path to converge.</p>
 *
 * <p>Off by default ({@code freeway.cloud.event.keepalive-ms} unset): liveness
 * probing is traffic, and a deployment that already has TCP keepalive or a
 * service mesh may prefer to pay nothing for a second opinion.</p>
 */
final class MeshKeepalive implements AutoCloseable {

    /** Sends one ping; returns false when the transport refused it. */
    interface Pinger {
        boolean ping(String payload);
    }

    private final ScheduledExecutorService scheduler;
    private final Pinger pinger;
    private final Runnable onTimeout;
    private final long intervalMillis;
    private final String payload;
    private final AtomicBoolean awaitingPong = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Volatile so close() can cancel it; a missed cancel would leave a no-op
     *  task firing for the life of the scheduler — one per closed connection. */
    private volatile ScheduledFuture<?> ticker;

    MeshKeepalive(ScheduledExecutorService scheduler, Pinger pinger, Runnable onTimeout,
                  long intervalMillis, String payload) {
        this.scheduler = scheduler;
        this.pinger = pinger;
        this.onTimeout = onTimeout;
        this.intervalMillis = intervalMillis;
        this.payload = payload;
    }

    void start() {
        ScheduledFuture<?> scheduled = scheduler.scheduleWithFixedDelay(
            this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        ticker = scheduled;
        if (closed.get()) {
            // close() ran between the schedule and the assignment: it saw no
            // future to cancel, so this side has to.
            scheduled.cancel(false);
        }
    }

    /** Called by the owner when a pong arrives: answers the in-flight probe. */
    void pongReceived() {
        awaitingPong.set(false);
    }

    private void tick() {
        if (closed.get()) {
            return;
        }
        if (!awaitingPong.compareAndSet(false, true)) {
            // The previous probe is still unanswered: the peer is not reading.
            // Close first, so the owner's teardown cannot be raced by our own
            // next tick.
            if (closed.compareAndSet(false, true)) {
                onTimeout.run();
            }
            return;
        }
        if (!pinger.ping(payload)) {
            // The transport already refused the probe — the peer is gone.
            awaitingPong.set(false);
            if (closed.compareAndSet(false, true)) {
                onTimeout.run();
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        ScheduledFuture<?> current = ticker;
        if (current != null) {
            current.cancel(false);
            ticker = null;
        }
    }
}
