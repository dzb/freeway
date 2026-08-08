package com.jujin.freeway.http;

/**
 * Configuration keys for the HTTP module.
 * All keys share the {@code freeway.http} namespace.
 */
public final class HttpConfigKeys {
    private HttpConfigKeys() {}

    static final String PREFIX = "freeway.http";

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

    // ── Body ────────────────────────────────────────────────────

    /** Max request body size in bytes (default 10MB). */
    public static final String MAX_BODY_SIZE = PREFIX + ".max-body-size";

    // ── SSL / HTTPS ─────────────────────────────────────────────

    /** Set to true to enable HTTPS. Requires key-store and key-store-password. */
    public static final String SSL_ENABLED             = PREFIX + ".ssl.enabled";
    /** Path to the keystore file (PKCS12 or JKS). */
    public static final String SSL_KEY_STORE           = PREFIX + ".ssl.key-store";
    /** Password for the keystore file. */
    public static final String SSL_KEY_STORE_PASSWORD  = PREFIX + ".ssl.key-store-password";
    /** Keystore type: PKCS12 (default) or JKS. */
    public static final String SSL_KEY_STORE_TYPE      = PREFIX + ".ssl.key-store-type";
    /** Enable HTTP/2 over TLS via ALPN negotiation (default true). */
    public static final String SSL_HTTP2               = PREFIX + ".ssl.http2";
}
