package com.jujin.freeway.http.filter;

import java.util.Map;

/**
 * Produces the response body for the health endpoint.
 * The return value is serialized to JSON via {@code sendJson(200, result)}.
 * <p>
 * Bind a custom implementation to replace the default {@code {"status":"ok"}} response,
 * for example to check database connectivity or external service health.
 */
@FunctionalInterface
public interface HealthCheck {

    Object check();

    /**
     * Default health check that always returns {@code {"status":"ok"}}.
     */
    final class Default implements HealthCheck {
        @Override
        public Object check() {
            return Map.of("status", "ok");
        }
    }
}
