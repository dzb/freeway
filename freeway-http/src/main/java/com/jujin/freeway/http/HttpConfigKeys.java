package com.jujin.freeway.http;

/**
 * Configuration keys for the HTTP module.
 * All keys share the {@code freeway.http} namespace.
 */
public final class HttpConfigKeys {
    private HttpConfigKeys() {}

    static final String PREFIX = "freeway.http";
    static final String LEGACY_PREFIX = "freeway.web";

    // ── Server ────────────────────────────────────────────────

    public static final String SERVER_HOST           = PREFIX + ".server.host";
    public static final String SERVER_PORT           = PREFIX + ".server.port";
    public static final String SERVER_BACKLOG        = PREFIX + ".server.backlog";
    public static final String SERVER_SHUTDOWN_GRACE = PREFIX + ".server.shutdown-grace";

    // ── CORS ──────────────────────────────────────────────────

    public static final String CORS_ENABLED           = PREFIX + ".cors.enabled";
    public static final String CORS_ALLOWED_ORIGINS   = PREFIX + ".cors.allowed-origins";
    public static final String CORS_ALLOWED_METHODS   = PREFIX + ".cors.allowed-methods";
    public static final String CORS_ALLOWED_HEADERS   = PREFIX + ".cors.allowed-headers";
    public static final String CORS_EXPOSED_HEADERS   = PREFIX + ".cors.exposed-headers";
    public static final String CORS_MAX_AGE           = PREFIX + ".cors.max-age";
    public static final String CORS_ALLOW_CREDENTIALS = PREFIX + ".cors.allow-credentials";

    // ── Health ────────────────────────────────────────────────

    public static final String HEALTH_ENABLED = PREFIX + ".health.enabled";
    public static final String HEALTH_PATH    = PREFIX + ".health.path";
}
