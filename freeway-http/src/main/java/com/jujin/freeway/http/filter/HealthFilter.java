package com.jujin.freeway.http.filter;

import java.util.Objects;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.http.HttpConfigKeys;
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

    /** Where an unset or blank {@code freeway.http.health.path} lands. */
    public static final String DEFAULT_PATH = "/healthz";

    private static final SymbolSpec<Boolean> ENABLED =
        SymbolSpec.of(HttpConfigKeys.HEALTH_ENABLED, Boolean.class, null);
    private static final SymbolSpec<String> PATH =
        SymbolSpec.of(HttpConfigKeys.HEALTH_PATH, String.class, null);

    private final boolean enabled;
    private final String healthPath;
    private final HealthCheck healthCheck;

    /** The one statement of an unset health policy: enabled, at {@link #DEFAULT_PATH},
     *  answering with the default check. */
    public static HealthFilter defaults() {
        return new HealthFilter(true, DEFAULT_PATH, HealthCheck.ALWAYS_OK);
    }

    /**
     * The policy as {@code freeway.http.health.*} answers it, with {@code check}
     * as the answer to probe: the check is a bound service, not a key, so it is
     * supplied rather than resolved.
     */
    public static HealthFilter from(SymbolSource symbols, HealthCheck check) {
        HealthFilter health = defaults().withCheck(check);
        health = health.withEnabled(symbols.resolve(ENABLED.orDefault(health.enabled())));
        health = health.withPath(symbols.resolve(PATH.orDefault(health.healthPath())));
        return health;
    }

    public HealthFilter(boolean enabled, String healthPath, HealthCheck healthCheck) {
        this.enabled = enabled;
        this.healthPath = normalize(healthPath);
        this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
    }

    public boolean enabled() {
        return enabled;
    }

    public String healthPath() {
        return healthPath;
    }

    public HealthCheck healthCheck() {
        return healthCheck;
    }

    /** Same policy with the probe switched on or off — off is a pass-through. */
    public HealthFilter withEnabled(boolean enabled) {
        return new HealthFilter(enabled, healthPath, healthCheck);
    }

    /** Same policy answering at this path. */
    public HealthFilter withPath(String healthPath) {
        return new HealthFilter(enabled, healthPath, healthCheck);
    }

    /** Same policy answering with this check. */
    public HealthFilter withCheck(HealthCheck healthCheck) {
        return new HealthFilter(enabled, healthPath, healthCheck);
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
            return DEFAULT_PATH;
        }
        return PathPattern.normalizePath(path);
    }
}
