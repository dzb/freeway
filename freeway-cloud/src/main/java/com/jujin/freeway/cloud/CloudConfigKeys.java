package com.jujin.freeway.cloud;

/**
 * Central config keys for {@code freeway-cloud}. Follows the {@code HttpConfigKeys} /
 * {@code DbConfigKeys} convention: one constant class, {@code freeway.cloud} prefix.
 */
public final class CloudConfigKeys {
    private CloudConfigKeys() {}

    static final String PREFIX = "freeway.cloud";

    // ── Config ─────────────────────────────────────────────
    public static final String CONFIG_TYPE        = PREFIX + ".config.type";
    public static final String CONFIG_FILE        = PREFIX + ".config.file";

    // ── Secret ─────────────────────────────────────────────
    public static final String SECRET_TYPE        = PREFIX + ".secret.type";
    public static final String SECRET_FILE        = PREFIX + ".secret.file";

    // ── Object Storage ─────────────────────────────────────
    public static final String STORAGE_TYPE       = PREFIX + ".storage.type";
    public static final String STORAGE_BASE_PATH  = PREFIX + ".storage.base-path";

    // ── Discovery / Registry ───────────────────────────────
    public static final String DISCOVERY_TYPE        = PREFIX + ".discovery.type";
    public static final String REGISTRY_TYPE         = PREFIX + ".registry.type";
    public static final String REGISTRY_SERVICE_ID   = PREFIX + ".registry.service-id";
    public static final String REGISTRY_SERVICE_HOST = PREFIX + ".registry.service-host";
    /** Scheme registered for this instance (http or https); default http. */
    public static final String REGISTRY_SERVICE_SCHEME = PREFIX + ".registry.service-scheme";
    public static final String REGISTRY_SERVICE_PORT = PREFIX + ".registry.service-port";
    public static final String REGISTRY_SERVICE_INSTANCE_ID = PREFIX + ".registry.service-instance-id";

    // ── RPC（远程调用）──────────────────────────────────────
    public static final String RPC_CONNECT_TIMEOUT     = PREFIX + ".rpc.connect-timeout";
    public static final String RPC_REQUEST_TIMEOUT     = PREFIX + ".rpc.request-timeout";
    public static final String RPC_RETRY_MAX_ATTEMPTS  = PREFIX + ".rpc.retry.max-attempts";
    public static final String RPC_RETRY_BACKOFF_BASE  = PREFIX + ".rpc.retry.backoff-base";
    public static final String RPC_RETRY_BACKOFF_MAX   = PREFIX + ".rpc.retry.backoff-max";
    public static final String RPC_CB_ENABLED          = PREFIX + ".rpc.circuit-breaker.enabled";
    public static final String RPC_CB_FAILURE_THRESHOLD = PREFIX + ".rpc.circuit-breaker.failure-threshold";
    public static final String RPC_CB_OPEN_WINDOW      = PREFIX + ".rpc.circuit-breaker.open-window";
    public static final String RPC_RATE_LIMIT_ENABLED  = PREFIX + ".rpc.rate-limit.enabled";
    public static final String RPC_RATE_LIMIT_PER_SECOND = PREFIX + ".rpc.rate-limit.per-second";
    public static final String RPC_TRACE_ENABLED       = PREFIX + ".rpc.trace.enabled";

    // ── RPC / TLS ───────────────────────────────────────────
    public static final String RPC_TLS_KEY_STORE          = PREFIX + ".rpc.tls.key-store";
    public static final String RPC_TLS_KEY_STORE_PASSWORD = PREFIX + ".rpc.tls.key-store-password";
    public static final String RPC_TLS_TRUST_STORE        = PREFIX + ".rpc.tls.trust-store";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD = PREFIX + ".rpc.tls.trust-store-password";

    // ── Auth propagation ────────────────────────────────────
    /** Off by default: inbound {@code x-principal} extraction trusts client
     *  headers and must be enabled explicitly (ideally only inside a trusted
     *  service mesh / with an ext token-verifying security module). */
    public static final String AUTH_EXTRACT_ENABLED = PREFIX + ".auth.extract.enabled";
}
