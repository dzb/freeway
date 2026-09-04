package com.jujin.freeway.cloud;
import com.jujin.freeway.cloud.rpc.CloudHttpClientDefault;

/**
 * Central config keys and canonical defaults for {@code freeway-cloud}.
 * Keys share the {@code freeway.cloud} prefix; {@code *DEFAULT} constants and
 * endpoint path literals deliberately live beside them because the config and
 * library-fallback layers share the same values (unlike the pure key catalogs
 * in {@code HttpConfigKeys}/{@code DbConfigKeys}).
 */
public final class CloudConfigKeys {
    private CloudConfigKeys() {}

    static final String PREFIX = "freeway.cloud";

    // ── Secret ─────────────────────────────────────────────
    public static final String SECRET_TYPE        = PREFIX + ".secret.type";
    public static final String SECRET_FILE        = PREFIX + ".secret.file";
    /** Optional allowlist: when set, only these symbol names resolve from the
     *  secret store (see {@code SecretSymbolSource} for why that matters). */
    public static final String SECRET_KEYS        = PREFIX + ".secret.keys";

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
    /** Seconds a failure stays in the sliding window before it drops out of the count. */
    public static final String RPC_CB_FAILURE_WINDOW   = PREFIX + ".rpc.circuit-breaker.failure-window";
    public static final String RPC_CB_OPEN_WINDOW      = PREFIX + ".rpc.circuit-breaker.open-window";
    public static final String RPC_RATE_LIMIT_ENABLED  = PREFIX + ".rpc.rate-limit.enabled";
    public static final String RPC_RATE_LIMIT_PER_SECOND = PREFIX + ".rpc.rate-limit.per-second";
    public static final String RPC_TRACE_ENABLED       = PREFIX + ".rpc.trace.enabled";

    // Canonical retry/breaker defaults — shared by CloudResilienceModule
    // (config fallbacks) and CloudHttpClientDefault (library fallback when
    // the resilience module is not installed), so the two layers cannot
    // drift apart. Rate limiting itself defaults to disabled and uses
    // RateLimiter.UNLIMITED on both paths; the per-second default below only
    // feeds CloudResilienceModule when rate limiting is enabled.
    public static final int RPC_RETRY_MAX_ATTEMPTS_DEFAULT    = 3;
    public static final long RPC_RETRY_BACKOFF_BASE_DEFAULT   = 100;
    public static final long RPC_RETRY_BACKOFF_MAX_DEFAULT    = 5000;
    public static final int RPC_CB_FAILURE_THRESHOLD_DEFAULT  = 5;
    public static final long RPC_CB_FAILURE_WINDOW_DEFAULT    = 60;
    public static final long RPC_CB_OPEN_WINDOW_DEFAULT       = 30;
    public static final double RPC_RATE_LIMIT_PER_SECOND_DEFAULT = 100;

    // ── RPC / TLS ───────────────────────────────────────────
    public static final String RPC_TLS_KEY_STORE          = PREFIX + ".rpc.tls.key-store";
    public static final String RPC_TLS_KEY_STORE_PASSWORD = PREFIX + ".rpc.tls.key-store-password";
    public static final String RPC_TLS_TRUST_STORE        = PREFIX + ".rpc.tls.trust-store";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD = PREFIX + ".rpc.tls.trust-store-password";

    // ── CloudEventBus（EventBus 的跨节点事件网格, 见 docs/freeway-cloud-events-design.md）──
    public static final String EVENTS_ENABLED        = PREFIX + ".events.enabled";
    public static final String EVENTS_PEERS          = PREFIX + ".events.peers";
    public static final String EVENTS_SUBSCRIPTIONS  = PREFIX + ".events.subscriptions";
    public static final String EVENTS_ALLOWED_TYPES  = PREFIX + ".events.allowed-types";
    public static final String EVENTS_ALLOWED_TOPICS = PREFIX + ".events.allowed-topics";
    /** Shared secret the mesh handshake must present; blank = no peer auth. */
    public static final String EVENTS_TOKEN      = PREFIX + ".events.token";
    /** Off by default: dedup changes delivery semantics (an event reaching
     *  this node over two transports is delivered once) and costs memory, so
     *  it is opt-in rather than a side effect of installing a second
     *  transport. Only meaningful when inbound events carry the bus-minted
     *  wire id. */
    public static final String EVENTS_DEDUP_ENABLED  = PREFIX + ".events.dedup.enabled";
    /** How many inbound ids to remember — the window in which a second copy
     *  of an event is still recognized. Too small and a slow second copy
     *  slips through; too large and the window costs memory for nothing. */
    public static final String EVENTS_DEDUP_CAPACITY = PREFIX + ".events.dedup.capacity";
    public static final int EVENTS_DEDUP_CAPACITY_DEFAULT = 4096;
    public static final String EVENTS_PATH_DEFAULT   = "/cloud/events";

    // ── CloudEventBus networking timeouts (defaults mirror the prior hard-coded values) ──
    /** Socket connect timeout for outbound mesh peer dials. */
    public static final String EVENTS_CONNECT_TIMEOUT_MS   = PREFIX + ".events.connect-timeout-ms";
    public static final long EVENTS_CONNECT_TIMEOUT_MS_DEFAULT   = 3000;
    /** A peer that accepts the socket but never answers the hello must not pin
     *  a half-open connection forever. */
    public static final String EVENTS_HANDSHAKE_TIMEOUT_MS = PREFIX + ".events.handshake-timeout-ms";
    public static final long EVENTS_HANDSHAKE_TIMEOUT_MS_DEFAULT = 10000;
    /** Reconnect backoff floor / ceiling (exponential, capped). */
    public static final String EVENTS_BACKOFF_BASE_MS = PREFIX + ".events.backoff-base-ms";
    public static final long EVENTS_BACKOFF_BASE_MS_DEFAULT = 1000;
    public static final String EVENTS_BACKOFF_MAX_MS  = PREFIX + ".events.backoff-max-ms";
    public static final long EVENTS_BACKOFF_MAX_MS_DEFAULT  = 30000;

    // ── Auth propagation ────────────────────────────────────
    /** Off by default: inbound {@code x-principal} extraction trusts client
     *  headers and must be enabled explicitly (ideally only inside a trusted
     *  service mesh / with an ext token-verifying security module). */
    public static final String AUTH_EXTRACT_ENABLED = PREFIX + ".auth.extract.enabled";
}
