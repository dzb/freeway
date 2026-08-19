package com.jujin.freeway.cloud.resilience;

/**
 * Circuit breaker: sliding failure window + half-open probe. Callers gate on
 * {@link #allowRequest()} before each attempt and report the outcome via
 * {@link #onSuccess()}/{@link #onFailure()}.
 */
public interface CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Current state. */
    State state();

    /** False when the circuit is OPEN (or a HALF_OPEN probe is already in flight). */
    boolean allowRequest();

    /** Reports a successful call (closes a HALF_OPEN probe, resets the failure window). */
    void onSuccess();

    /** Reports a failed call (opens the circuit at the threshold). */
    void onFailure();

    /** Manual reset to CLOSED. */
    void reset();

    CircuitBreaker NOOP = new CircuitBreaker() {
        @Override
        public State state() {
            return State.CLOSED;
        }

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure() {
        }

        @Override
        public void reset() {
        }
    };
}
