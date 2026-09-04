package com.jujin.freeway.cloud.resilience;

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
 * <p><b>Threading model.</b> All state transitions (state, failure window,
 * probe bookkeeping) happen under a single {@code probeLock}; the volatile
 * {@code state} read outside the lock is a fast-path filter only, and every
 * transition re-reads the state under the lock before acting. This makes the
 * machine race-free: a stale success can no longer skip the open window, a
 * re-arm cannot admit a call while the circuit is OPEN, and callers admitted
 * before the circuit opened (no probe epoch) can no longer settle HALF_OPEN
 * — their late outcomes are ignored, exactly like stale OPEN-era outcomes.
 * A half-open probe that is admitted but never reported (lost request, local
 * rejection before the transport call) times out after {@code openWindow}
 * and re-arms the circuit, so the breaker can never wedge in HALF_OPEN.
 */
public final class CircuitBreakerDefault implements CircuitBreaker {

    private final int failureThreshold;
    private final Duration failureWindow;
    private final Duration openWindow;

    /** Fast-path read-only view; every transition happens under {@link #probeLock}. */
    private volatile State state = State.CLOSED;
    private final ArrayDeque<Long> failureTimes = new ArrayDeque<>(); // nanos
    private volatile long openedAtNanos;
    /** Fast-path admission gate for the OPEN→HALF_OPEN probe; transitions under {@link #probeLock}. */
    private final AtomicBoolean probeInFlight = new AtomicBoolean();
    private volatile long probeStartedAtNanos;
    /** Monotonically increases each time a probe is admitted; callbacks use it
     *  to ignore results from probes that were superseded by a re-arm. */
    private long probeEpoch;
    private final ThreadLocal<Long> admittedProbeEpoch = new ThreadLocal<>();
    /** Single lock for ALL state transitions and counter access. */
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
            if (System.nanoTime() - openedAtNanos < openWindow.toNanos()
                    || !probeInFlight.compareAndSet(false, true)) {
                return false;
            }
            synchronized (probeLock) {
                // The state may have settled between the volatile read / CAS
                // and this lock (another callback finishing, reset()). Acting
                // on the stale snapshot would bypass the breaker or wedge the
                // probe bookkeeping — re-read before arming.
                if (state != State.OPEN) {
                    probeInFlight.set(false);
                    return false;
                }
                probeEpoch++;
                probeStartedAtNanos = System.nanoTime();
                admittedProbeEpoch.set(probeEpoch);
                state = State.HALF_OPEN;
            }
            return true;
        }
        // HALF_OPEN: only the single admitted probe may run. A probe that was
        // admitted but never reported (transport failure outside the callback
        // contract, local rejection, lost result) times out; the next caller
        // then becomes the fresh probe instead of the circuit wedging in
        // HALF_OPEN forever. Re-reading the state under the lock keeps a
        // circuit that settled (probe failure → OPEN) from admitting a call.
        synchronized (probeLock) {
            if (state != State.HALF_OPEN) {
                admittedProbeEpoch.remove();
                return false;
            }
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
                if (admitted != probeEpoch || state != State.HALF_OPEN) {
                    return; // stale probe superseded by a re-arm or already settled
                }
                probeInFlight.set(false);
                failureTimes.clear();
                state = State.CLOSED;
            }
            return;
        }
        // A caller without a probe epoch was admitted before the circuit
        // opened: while OPEN its success is stale (must not skip the open
        // window), and while HALF_OPEN the outcome belongs to the admitted
        // probe — ignoring it here keeps the probe's verdict authoritative.
        synchronized (probeLock) {
            if (state != State.CLOSED) {
                return;
            }
            failureTimes.clear();
        }
    }

    @Override
    public void onFailure() {
        Long admitted = admittedProbeEpoch.get();
        if (admitted != null) {
            admittedProbeEpoch.remove();
            synchronized (probeLock) {
                if (admitted != probeEpoch || state != State.HALF_OPEN) {
                    return; // stale probe superseded by a re-arm or already settled
                }
                probeInFlight.set(false);
                state = State.OPEN;
                openedAtNanos = System.nanoTime();
            }
            return;
        }
        // Same gate as onSuccess: pre-open callers are stale in every state
        // but CLOSED (OPEN = stale failure that must not re-extend the window;
        // HALF_OPEN = the probe owns the outcome).
        synchronized (probeLock) {
            if (state != State.CLOSED) {
                return;
            }
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
        synchronized (probeLock) {
            // Bump the epoch so a still-in-flight probe's callbacks become
            // stale instead of settling a circuit that reset() just closed.
            probeEpoch++;
            probeInFlight.set(false);
            failureTimes.clear();
            state = State.CLOSED;
        }
    }

    @Override
    public CircuitBreaker newShard() {
        return new CircuitBreakerDefault(failureThreshold, failureWindow, openWindow);
    }
}
