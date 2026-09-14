package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpStatus;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.PathPattern;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Filter that intercepts the health endpoint before routing.
 * <p>
 * Configured via {@link com.jujin.freeway.http.HttpConfigKeys#HEALTH_ENABLED} (default {@code true}) and
 * {@link com.jujin.freeway.http.HttpConfigKeys#HEALTH_PATH} (default {@code /healthz}).
 * The response body is produced by {@link HealthCheck}; bind a custom
 * implementation to replace the default {@code {"status":"ok"}}.
 */
public final class HealthFilter implements HttpFilter {

    private final boolean enabled;
    private final String healthPath;
    private final HealthCheck healthCheck;

    /** Default: enabled, /healthz path, default health check. */
    public static final HealthFilter DEFAULT = new HealthFilter(
        true, "/healthz", HealthCheck.ALWAYS_OK);

    public HealthFilter(boolean enabled, String healthPath, HealthCheck healthCheck) {
        this.enabled = enabled;
        this.healthPath = normalize(healthPath);
        this.healthCheck = healthCheck;
    }

    /** Returns false when health checks are disabled — this filter is then a no-op pass-through. */
    public boolean isActive() {
        return enabled;
    }

    @Override
    public int order() {
        return -50; // between CORS (-100) and application filters (0)
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (enabled && "GET".equalsIgnoreCase(ctx.method())
                && healthPath.equals(PathPattern.normalizePath(
                    ctx.path()))) {
            ctx.sendJson(HttpStatus.OK, healthCheck.check());
            return;
        }
        next.handle(ctx);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/healthz";
        }
        return PathPattern.normalizePath(path);
    }
}
