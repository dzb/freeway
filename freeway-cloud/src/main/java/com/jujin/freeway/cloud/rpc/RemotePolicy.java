package com.jujin.freeway.cloud.rpc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-operation transport policy for a typed remote client.
 *
 * <p>Read reflectively by {@link RemoteProxyFactory} at dispatch time, on the
 * method first and then on the declaring interface. It selects only the call's
 * bounds; retry/breaker/rate-limit thresholds and the service-level retry
 * budget remain the client's existing configuration.
 *
 * <pre>{@code
 * public interface ReportApi {
 *     @RemotePolicy(timeoutMillis = 30_000)          // heavy read
 *     Report generate(ReportRequest request);
 *
 *     @RemotePolicy(timeoutMillis = 2_000, maxAttempts = 1) // write: never replay
 *     void publish(Report report);
 * }
 * }</pre>
 *
 * <p>{@link #timeoutMillis()} bounds the whole call (all attempts), overriding
 * the factory-level timeout; {@code 0} inherits it. {@link #maxAttempts()} is
 * the total number of attempts, so {@code 1} disables retry and {@code 2}
 * allows the initial attempt plus one retry; {@code 0} inherits the client's
 * {@link com.jujin.freeway.cloud.resilience.Retryer}. Replay of an ambiguous
 * outcome still requires {@link Idempotent}.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RemotePolicy {

    /** End-to-end deadline in milliseconds; 0 = inherit the factory timeout. */
    long timeoutMillis() default 0;

    /** Total attempts including the first; 0 = inherit the client Retryer. */
    int maxAttempts() default 0;
}
