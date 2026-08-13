package com.jujin.freeway.http.filter;

import java.nio.charset.StandardCharsets;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.MediaTypes;
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
    // Pre-computed response body for the default health check
    private static final byte[] DEFAULT_RESPONSE =
        "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    /** Default: enabled, /healthz path, default health check. */
    public static final HealthFilter DEFAULT = new HealthFilter(
        true, "/healthz", new HealthCheck.Default());

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
            if (healthCheck instanceof HealthCheck.Default) {
                ctx.setStatus(200).setHeader(
                        "Content-Type", MediaTypes.JSON_UTF8)
                    .output(DEFAULT_RESPONSE);
            } else {
                ctx.sendJson(200, healthCheck.check());
            }
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
