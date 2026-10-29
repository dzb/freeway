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
 * <p>Two numbers, not one: {@code intervalMillis} is the probe <em>cadence</em>,
 * {@code timeoutMillis} the pong <em>budget</em>. When no probe is in flight a
 * tick sends a ping and records when; while one is in flight further ticks send
 * nothing, and only a budget that expires declares the peer dead. An earlier
 * shape used "previous probe still armed at the next tick" as the timeout,
 * which made the budget equal to the interval — at 50 ms, one JVM pause or
 * dispatch delay was enough to close a healthy loopback link. The budget is
 * derived by {@link #timeoutFor(long)}: three intervals, with a one-second
 * floor for pause-sized stalls.</p>
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

    /** The timeout budget for an interval: three probes, never under one second. */
    static long timeoutFor(long intervalMillis) {
        long triple = intervalMillis > Long.MAX_VALUE / 3
            ? Long.MAX_VALUE
            : intervalMillis * 3;
        return Math.max(triple, 1_000);
    }

    private final ScheduledExecutorService scheduler;
    private final Pinger pinger;
    private final Runnable onTimeout;
    private final long intervalMillis;
    private final long timeoutMillis;
    private final String payload;
    private final AtomicBoolean awaitingPong = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** When the in-flight probe was sent; read only by the tick thread. */
    private long probeSentNanos;
    /** Volatile so close() can cancel it; a missed cancel would leave a no-op
     *  task firing for the life of the scheduler — one per closed connection. */
    private volatile ScheduledFuture<?> ticker;

    MeshKeepalive(ScheduledExecutorService scheduler, Pinger pinger, Runnable onTimeout,
                  long intervalMillis, long timeoutMillis, String payload) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.scheduler = scheduler;
        this.pinger = pinger;
        this.onTimeout = onTimeout;
        this.intervalMillis = intervalMillis;
        this.timeoutMillis = Math.max(timeoutMillis, intervalMillis);
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
        if (awaitingPong.get()) {
            // One probe in flight — a pong clears it, and only an expired
            // budget declares the peer dead. Do not stack pings.
            if (elapsedMillis() < timeoutMillis) {
                return;
            }
            if (closed.compareAndSet(false, true)) {
                onTimeout.run();
            }
            return;
        }
        if (!awaitingPong.compareAndSet(false, true)) {
            return;
        }
        probeSentNanos = System.nanoTime();
        if (!pinger.ping(payload)) {
            // The transport already refused the probe — the peer is gone.
            awaitingPong.set(false);
            if (closed.compareAndSet(false, true)) {
                onTimeout.run();
            }
        }
    }

    private long elapsedMillis() {
        return (System.nanoTime() - probeSentNanos) / 1_000_000;
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
