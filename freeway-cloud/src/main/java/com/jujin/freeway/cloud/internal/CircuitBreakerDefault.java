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
 */
public final class CircuitBreakerDefault implements CircuitBreaker {

    private final int failureThreshold;
    private final Duration failureWindow;
    private final Duration openWindow;

    private volatile State state = State.CLOSED;
    private final ArrayDeque<Long> failureTimes = new ArrayDeque<>(); // nanos
    private volatile long openedAtNanos;
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    public CircuitBreakerDefault(int failureThreshold, Duration failureWindow, Duration openWindow) {
        this.failureThreshold = failureThreshold;
        this.failureWindow = failureWindow;
        this.openWindow = openWindow;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public boolean allowRequest() {
        State current = state;
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (System.nanoTime() - openedAtNanos >= openWindow.toNanos()
                    && probeInFlight.compareAndSet(false, true)) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        return false; // HALF_OPEN: only the single admitted probe may run
    }

    @Override
    public void onSuccess() {
        probeInFlight.set(false);
        synchronized (this) {
            failureTimes.clear();
        }
        state = State.CLOSED;
    }

    @Override
    public void onFailure() {
        if (state == State.HALF_OPEN) {
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
}
