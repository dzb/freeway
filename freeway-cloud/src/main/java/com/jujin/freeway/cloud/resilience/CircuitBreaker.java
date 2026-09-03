package com.jujin.freeway.cloud.resilience;

/**
 * Circuit breaker: sliding failure window + half-open probe. Callers gate on
 * {@link #allowRequest()} before each attempt and report the outcome via
 * {@link #onSuccess()}/{@link #onFailure()}.
 *
 * <p><b>Report the outcome from the admitting thread.</b> A half-open probe is
 * handed to whoever {@link #allowRequest()} let through, so
 * {@code onSuccess}/{@code onFailure} must run on that same thread — the
 * framework implementation tracks the admitted probe per thread. Settling it
 * elsewhere leaves the probe unaccounted and the circuit in HALF_OPEN until the
 * open window re-arms it.</p>
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

    /** A per-service shard derived from this breaker: same policy,
     *  independent state. The default returns {@code this} (shared) for
     *  stateless or externally-managed implementations; stateful framework
     *  implementations override it so one failing service cannot poison
     *  others. */
    default CircuitBreaker newShard() {
        return this;
    }

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
