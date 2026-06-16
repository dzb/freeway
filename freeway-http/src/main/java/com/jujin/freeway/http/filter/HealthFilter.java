package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.annotation.Value;

/**
 * Filter that intercepts the health endpoint before routing.
 * <p>
 * Configured via {@code web.health.enabled} (default {@code true}) and
 * {@code web.health.path} (default {@code /healthz}).
 * The response body is produced by {@link HealthCheck}; bind a custom
 * implementation to replace the default {@code {"status":"ok"}}.
 */
public final class HealthFilter implements HttpFilter {

    private final boolean enabled;
    private final String healthPath;
    private final HealthCheck healthCheck;

    public HealthFilter(
        @Value("${web.health.enabled:true}") boolean enabled,
        @Value("${web.health.path:/healthz}") String healthPath,
        HealthCheck healthCheck
    ) {
        this.enabled = enabled;
        this.healthPath = normalize(healthPath);
        this.healthCheck = healthCheck;
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (enabled && "GET".equalsIgnoreCase(ctx.method()) && healthPath.equals(ctx.path())) {
            ctx.sendJson(200, healthCheck.check());
            return;
        }
        next.handle(ctx);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/healthz";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
