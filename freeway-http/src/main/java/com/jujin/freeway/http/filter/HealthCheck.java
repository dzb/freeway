package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HealthResult;

/**
 * Produces the liveness result for the HTTP health endpoint. Bind a custom
 * implementation with {@code .primary()} to replace the default; the result
 * shape is shared with cloud readiness contributors.
 */
@FunctionalInterface
public interface HealthCheck {

    HealthResult check();

    /** The default health check: always {@link HealthResult#ok()}. */
    HealthCheck ALWAYS_OK = HealthResult::ok;
}
