package com.jujin.freeway.cloud.resilience;

/**
 * Retry policy: how many retries and how long to back off. Whether a failure
 * is retryable at all is decided by the caller (e.g. transport/5xx yes, 4xx
 * no) — this interface only bounds attempts and pacing.
 */
public interface Retryer {

    /** @param attempt attempt counter, starting at 0; true = retry again */
    boolean shouldRetry(int attempt, Throwable failure);

    /** Backoff in milliseconds before the given attempt's retry. */
    long backoffMillis(int attempt);

    Retryer NO_RETRY = new Retryer() {
        @Override
        public boolean shouldRetry(int attempt, Throwable failure) {
            return false;
        }

        @Override
        public long backoffMillis(int attempt) {
            return 0;
        }
    };
}
