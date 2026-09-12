package com.jujin.freeway.http;

/**
 * Configuration keys for the HTTP module.
 * All keys share the {@code freeway.http} namespace.
 *
 * <p><b>The surface has three tiers, and only the first asks for a decision.</b>
 * The grouping below is that classification, not a topic list:
 * <ol>
 *   <li><b>Decision keys</b> — where to listen and how to secure it:
 *       {@link #SERVER_HOST}, {@link #SERVER_PORT}, {@link #SSL_KEY_STORE} with
 *       its password (HTTPS is presence-driven), and
 *       {@link #CORS_ALLOWED_ORIGINS} (the permissive {@code *} default is for
 *       development; a deployment should name its origins). Five keys, and a
 *       service with no TLS and no browser clients needs two of them.</li>
 *   <li><b>Default-optimal switches</b> — one flag per feature (compression,
 *       access log, CORS, health). The default is the recommended posture; the
 *       key exists so a deployment can turn the feature off, not because it
 *       needs a decision. {@link #SSL_ENABLED} is the one tri-state here: unset
 *       defers to keystore presence, and {@code false} is a kill switch.</li>
 *   <li><b>Advanced (rare)</b> — operational tuning whose default is the
 *       value the framework recommends: socket backlog/buffers/timeouts, the
 *       HTTP/2 reset guard, the compression threshold, the body-size ceiling,
 *       the remaining CORS response details, and the TLS protocol/cipher/SNI/
 *       trust-store selection. Some of these default to the platform/JDK value
 *       ({@code 0}, empty); others default to a tuned number. Either way, set
 *       one only when you know why.</li>
 * </ol>
 *
 * <p>There is deliberately no aggregate switch on top of the advanced tier:
 * every cluster already has its flag ({@code compression.enabled},
 * {@code health.enabled}, a {@code 0}/empty "off" in the tuning keys), and a
 * second way to say the same thing is the duplication this catalog avoids.
 */
public final class HttpConfigKeys {
    private HttpConfigKeys() {}

    static final String PREFIX = "freeway.http";

    // ── Decision keys ─────────────────────────────────────────

    /** Bind address (default {@code 127.0.0.1}; a container binds {@code 0.0.0.0}). */
    public static final String SERVER_HOST           = PREFIX + ".server.host";
    /** Listen port (default 8080; {@code 0} = system-assigned). */
    public static final String SERVER_PORT           = PREFIX + ".server.port";
    /** Path to the keystore file (PKCS12 or JKS). Presence alone activates
     *  HTTPS (unless {@code ssl.enabled=false} suppresses it). */
    public static final String SSL_KEY_STORE           = PREFIX + ".ssl.key-store";
    /** Password for the keystore file. */
    public static final String SSL_KEY_STORE_PASSWORD  = PREFIX + ".ssl.key-store-password";
    /** Allowed origins. The default {@code *} is a development posture —
     *  name the real origins in a deployment (see the CORS block below). */
    public static final String CORS_ALLOWED_ORIGINS   = PREFIX + ".cors.allowed-origins";

    // ── Default-optimal switches ──────────────────────────────

    /** Max request body size in bytes (default 10MB). A framework policy, not
     *  a platform default: a service accepting uploads must decide this, or a
     *  large upload is rejected with 413. */
    public static final String MAX_BODY_SIZE = PREFIX + ".max-body-size";


    /** Master switch — presence-driven: an explicit value wins ({@code true}
     *  on, {@code false} = kill switch suppressing a configured keystore);
     *  unset falls to keystore presence — a configured keystore is an HTTPS
     *  server, nothing set is plaintext. */
    public static final String SSL_ENABLED             = PREFIX + ".ssl.enabled";
    /** gzip response compression for compressible content (default true). */
    public static final String COMPRESSION_ENABLED   = PREFIX + ".compression.enabled";
    /** Text access log to stdout (default false). */
    public static final String ACCESS_LOG_ENABLED    = PREFIX + ".access-log.enabled";
    /** CORS filter on (default true); {@code false} serves no CORS headers. */
    public static final String CORS_ENABLED           = PREFIX + ".cors.enabled";
    /** Built-in {@code GET /healthz} endpoint on (default true). */
    public static final String HEALTH_ENABLED = PREFIX + ".health.enabled";

    // ── Advanced: server tuning (default = platform default) ──

    /** Accept queue size (default 0 = OS default). */
    public static final String SERVER_BACKLOG        = PREFIX + ".server.backlog";
    /** Grace period for in-flight requests on shutdown (default 2s). */
    public static final String SERVER_SHUTDOWN_GRACE = PREFIX + ".server.shutdown-grace";
    /** Socket read idle timeout (default 30s; 0 disables). */
    public static final String SERVER_READ_TIMEOUT   = PREFIX + ".server.read-timeout";
    /** Per-socket-write timeout (default 30s; 0 disables). */
    public static final String SERVER_WRITE_TIMEOUT  = PREFIX + ".server.write-timeout";
    /** Maximum concurrent connections (default 0 = unlimited). */
    public static final String SERVER_MAX_CONNECTIONS = PREFIX + ".server.max-connections";
    /** Desired SO_RCVBUF for accepted sockets (default 0 = OS default). */
    public static final String SERVER_RECEIVE_BUFFER = PREFIX + ".server.receive-buffer-size";
    /** Desired SO_SNDBUF for accepted sockets (default 0 = OS default). */
    public static final String SERVER_SEND_BUFFER    = PREFIX + ".server.send-buffer-size";
    /** Inbound RST_STREAM burst guard: cancels arriving before the server
     *  responded, beyond this count within the reset window, trip the
     *  connection with GOAWAY(ENHANCE_YOUR_CALM) (0 disables the guard). */
    public static final String H2_RESET_BURST_LIMIT = PREFIX + ".h2.reset-burst-limit";
    /** Sliding window for the reset burst guard (default 10s). A no-op while
     *  {@link #H2_RESET_BURST_LIMIT} is 0 — the guard returns before reading it. */
    public static final String H2_RESET_WINDOW      = PREFIX + ".h2.reset-window";
    // ── Advanced: per-feature detail ──────────────────────────

    /** Minimum response body size in bytes before gzip applies (default 256). */
    public static final String COMPRESSION_MIN_SIZE  = PREFIX + ".compression.min-size";
    /** Health endpoint path (default {@code /healthz}). */
    public static final String HEALTH_PATH    = PREFIX + ".health.path";
    /** Comma-separated allowed methods (default GET, POST, PUT, DELETE, PATCH, OPTIONS). */
    public static final String CORS_ALLOWED_METHODS   = PREFIX + ".cors.allowed-methods";
    /** Comma-separated allowed request headers (default Content-Type, Authorization). */
    public static final String CORS_ALLOWED_HEADERS   = PREFIX + ".cors.allowed-headers";
    /** Comma-separated response headers exposed to the browser (default empty). */
    public static final String CORS_EXPOSED_HEADERS   = PREFIX + ".cors.exposed-headers";
    /** Preflight cache lifetime in seconds (default 3600). */
    public static final String CORS_MAX_AGE           = PREFIX + ".cors.max-age";
    /** Allow credentials on cross-origin requests (default false). */
    public static final String CORS_ALLOW_CREDENTIALS = PREFIX + ".cors.allow-credentials";

    // ── Advanced: TLS selection (default = JDK default) ───────

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
    /** Comma-separated TLS protocol versions; empty = JDK default. */
    public static final String SSL_PROTOCOLS           = PREFIX + ".ssl.protocols";
    /** Comma-separated TLS cipher suite names; empty = JDK default. */
    public static final String SSL_CIPHERS             = PREFIX + ".ssl.ciphers";
    /** Optional directory of per-hostname keystores for SNI certificate
     *  selection; each file is named {@code <host>.p12} (or .jks), and
     *  {@code default.p12} overrides the key-store as the fallback. */
    public static final String SSL_SNI_DIRECTORY       = PREFIX + ".ssl.sni-directory";
    /** Certificate reload polling interval (0 disables hot reload). */
    public static final String SSL_RELOAD_INTERVAL     = PREFIX + ".ssl.reload-interval";
}
