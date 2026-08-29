package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.resilience.CircuitBreaker;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sliding-window {@link CircuitBreaker} with half-open probing, built on JDK
 * concurrency primitives:
 *
 * <ul>
 *   <li>CLOSED — failures within the {@code failureWindow} are counted; at
 *       {@code failureThreshold} the circuit opens. A success resets the
 *       window.</li>
 *   <li>OPEN — {@code allowRequest()} is false until {@code openWindow}
 *       elapses, then one half-open probe is admitted.</li>
 *   <li>HALF_OPEN — the probe outcome decides: success → CLOSED, failure →
 *       OPEN again.</li>
 * </ul>
 *
 * <p>Outcome callbacks are gated on the current state so stale results from
 * in-flight calls cannot corrupt the machine: a success that returns after
 * the circuit opened is ignored, and a failure that returns while OPEN does
 * not re-extend the open window. A half-open probe that is admitted but never
 * reported (lost request, local rejection before the transport call) times
 * out after {@code openWindow} and re-arms the circuit, so the breaker can
 * never wedge in HALF_OPEN.
 */
public final class CircuitBreakerDefault implements CircuitBreaker {

    private final int failureThreshold;
    private final Duration failureWindow;
    private final Duration openWindow;

    private volatile State state = State.CLOSED;
    private final ArrayDeque<Long> failureTimes = new ArrayDeque<>(); // nanos
    private volatile long openedAtNanos;
    private final AtomicBoolean probeInFlight = new AtomicBoolean();
    private volatile long probeStartedAtNanos;
    /** Monotonically increases each time a probe is admitted; callbacks use it
     *  to ignore results from probes that were superseded by a re-arm. */
    private long probeEpoch;
    private final ThreadLocal<Long> admittedProbeEpoch = new ThreadLocal<>();
    /** Guards only the rare lost-probe re-arm path so exactly one waiter
     *  becomes the fresh probe (single-probe invariant). */
    private final Object probeLock = new Object();

    public CircuitBreakerDefault(int failureThreshold, Duration failureWindow, Duration openWindow) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive: " + failureThreshold);
        }
        this.failureWindow = Duration.ofNanos(toPositiveNanos(failureWindow, "failureWindow"));
        this.openWindow = Duration.ofNanos(toPositiveNanos(openWindow, "openWindow"));
        this.failureThreshold = failureThreshold;
    }

    private static long toPositiveNanos(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + d);
        }
        return d.toNanos();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public boolean allowRequest() {
        State current = state;
        if (current == State.CLOSED) {
            admittedProbeEpoch.remove();
            return true;
        }
        if (current == State.OPEN) {
            if (System.nanoTime() - openedAtNanos >= openWindow.toNanos()
                    && probeInFlight.compareAndSet(false, true)) {
                long now = System.nanoTime();
                synchronized (probeLock) {
                    probeEpoch++;
                    probeStartedAtNanos = now;
                    admittedProbeEpoch.set(probeEpoch);
                }
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        // HALF_OPEN: only the single admitted probe may run. A probe that was
        // admitted but never reported (transport failure outside the callback
        // contract, local rejection, lost result) times out; the next caller
        // then becomes the fresh probe instead of the circuit wedging in
        // HALF_OPEN forever. The lock keeps concurrent waiters from all
        // deciding they are the fresh probe.
        synchronized (probeLock) {
            if (System.nanoTime() - probeStartedAtNanos > openWindow.toNanos()) {
                probeStartedAtNanos = System.nanoTime(); // re-arm: this call is the new probe
                probeEpoch++;
                admittedProbeEpoch.set(probeEpoch);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onSuccess() {
        Long admitted = admittedProbeEpoch.get();
        if (admitted != null) {
            admittedProbeEpoch.remove();
            synchronized (probeLock) {
                if (admitted != probeEpoch) {
                    return; // stale probe superseded by a re-arm
                }
            }
            if (state != State.HALF_OPEN) {
                return; // already settled by another callback
            }
            probeInFlight.set(false);
            synchronized (this) {
                failureTimes.clear();
            }
            state = State.CLOSED;
            return;
        }
        // A success that returns while the circuit is OPEN is stale (the call
        // was admitted before the open) — it must not yank the circuit back to
        // CLOSED and skip the open window.
        if (state == State.OPEN) {
            return;
        }
        probeInFlight.set(false);
        synchronized (this) {
            failureTimes.clear();
        }
        state = State.CLOSED;
    }

    @Override
    public void onFailure() {
        Long admitted = admittedProbeEpoch.get();
        if (admitted != null) {
            admittedProbeEpoch.remove();
            synchronized (probeLock) {
                if (admitted != probeEpoch) {
                    return; // stale probe superseded by a re-arm
                }
            }
            if (state != State.HALF_OPEN) {
                return; // already settled by another callback
            }
            probeInFlight.set(false);
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
            return;
        }
        State current = state;
        // A failure that returns while OPEN is stale (admitted before the
        // open) — counting it would re-extend the open window forever.
        if (current == State.OPEN) {
            return;
        }
        if (current == State.HALF_OPEN) {
            probeInFlight.set(false);
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
            return;
        }
        synchronized (this) {
            long now = System.nanoTime();
            failureTimes.addLast(now);
            while (!failureTimes.isEmpty()
                    && now - failureTimes.peekFirst() > failureWindow.toNanos()) {
                failureTimes.removeFirst();
            }
            if (failureTimes.size() >= failureThreshold) {
                state = State.OPEN;
                openedAtNanos = now;
                failureTimes.clear();
            }
        }
    }

    @Override
    public void reset() {
        probeInFlight.set(false);
        synchronized (this) {
            failureTimes.clear();
        }
        state = State.CLOSED;
    }

    @Override
    public CircuitBreaker newShard() {
        return new CircuitBreakerDefault(failureThreshold, failureWindow, openWindow);
    }
}
