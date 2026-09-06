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
    /** Socket read idle timeout (e.g. 30s; 0 disables). */
    public static final String SERVER_READ_TIMEOUT   = PREFIX + ".server.read-timeout";
    /** Per-socket-write timeout (e.g. 30s; 0 disables). */
    public static final String SERVER_WRITE_TIMEOUT  = PREFIX + ".server.write-timeout";
    /** Maximum concurrent connections (0 = unlimited). */
    public static final String SERVER_MAX_CONNECTIONS = PREFIX + ".server.max-connections";
    /** Desired SO_RCVBUF for accepted sockets (0 = OS default). */
    public static final String SERVER_RECEIVE_BUFFER = PREFIX + ".server.receive-buffer-size";
    /** Desired SO_SNDBUF for accepted sockets (0 = OS default). */
    public static final String SERVER_SEND_BUFFER    = PREFIX + ".server.send-buffer-size";

    // ── Compression / access log ─────────────────────────────────

    /** gzip response compression for compressible content (default true). */
    public static final String COMPRESSION_ENABLED   = PREFIX + ".compression.enabled";
    /** Minimum response body size in bytes before gzip applies (default 256). */
    public static final String COMPRESSION_MIN_SIZE  = PREFIX + ".compression.min-size";
    /** Text access log to stdout (default false). */
    public static final String ACCESS_LOG_ENABLED    = PREFIX + ".access-log.enabled";

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

    // ── HTTP/2 ──────────────────────────────────────────────────

    /** Inbound RST_STREAM burst guard: cancels arriving before the server
     *  responded, beyond this count within the reset window, trip the
     *  connection with GOAWAY(ENHANCE_YOUR_CALM) (0 disables the guard). */
    public static final String H2_RESET_BURST_LIMIT = PREFIX + ".h2.reset-burst-limit";
    /** Sliding window for the reset burst guard (e.g. 10s). */
    public static final String H2_RESET_WINDOW      = PREFIX + ".h2.reset-window";

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
    /** Optional truststore path for validating peer certificates. */
    public static final String SSL_TRUST_STORE         = PREFIX + ".ssl.trust-store";
    /** Password for the truststore file. */
    public static final String SSL_TRUST_STORE_PASSWORD = PREFIX + ".ssl.trust-store-password";
    /** Truststore type: PKCS12 (default) or JKS. */
    public static final String SSL_TRUST_STORE_TYPE    = PREFIX + ".ssl.trust-store-type";
    /** Require client certificates (mTLS). Default false. */
    public static final String SSL_CLIENT_AUTH         = PREFIX + ".ssl.client-auth";
    /** Comma-separated TLS protocol versions (e.g. TLSv1.3,TLSv1.2). */
    public static final String SSL_PROTOCOLS           = PREFIX + ".ssl.protocols";
    /** Comma-separated TLS cipher suite names. */
    public static final String SSL_CIPHERS             = PREFIX + ".ssl.ciphers";
    /** Optional directory of per-hostname keystores for SNI certificate
     *  selection; each file is named {@code <host>.p12} (or .jks), and
     *  {@code default.p12} overrides the key-store as the fallback. */
    public static final String SSL_SNI_DIRECTORY       = PREFIX + ".ssl.sni-directory";
    /** Certificate reload polling interval (0 disables hot reload). */
    public static final String SSL_RELOAD_INTERVAL     = PREFIX + ".ssl.reload-interval";
}
