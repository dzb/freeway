package com.jujin.freeway.cloud;

/**
 * Central config keys and canonical defaults for {@code freeway-cloud}.
 * Keys share the {@code freeway.cloud} prefix; {@code *DEFAULT} constants and
 * endpoint path literals deliberately live beside them because the config and
 * library-fallback layers share the same values (unlike the pure key catalogs
 * in {@code HttpConfigKeys}/{@code DbConfigKeys}).
 *
 * <p><b>The surface has three tiers, and the third is already compressed behind
 * an aggregate switch — that is the design, not a gap:</b>
 * <ol>
 *   <li><b>Decision keys</b> — the ones a deployment must think about:
 *       {@link #EVENT_PEERS} plus {@link #EVENT_TOKEN} (a multi-node mesh needs
 *       both; peers alone are the switch), {@link #RPC_TLS_KEY_STORE} with its
 *       password (mTLS, presence-driven), and {@link #SECRET_FILE} /
 *       {@link #SECRET_KEYS} (bootstrap-only, read from {@code -D}).</li>
 *   <li><b>Default-optimal</b> — the four {@code *.type} keys (empty = the
 *       built-in local backend), everything the registry derives from the HTTP
 *       server, and every feature flag whose default is the recommended
 *       posture ({@link #EVENT_ENABLED} is presence-driven,
 *       {@link #AUTH_EXTRACT_ENABLED} and {@link #EVENT_DEDUP_ENABLED} are
 *       opt-in, {@link #RPC_TRACE_ENABLED} is on).</li>
 *   <li><b>Advanced (rare)</b> — the tuning numbers, and <em>each cluster
 *       already names one aggregate that governs it</em>:
 *       {@link #RPC_RESILIENCE} ({@code auto} = the nine retry / breaker /
 *       limiter keys below govern, {@code off} = one kill switch for all of
 *       them), {@link #EVENT_ENABLED} (presence decides, so the four transport
 *       timeouts are only reachable once the mesh exists), and
 *       {@link #EVENT_DEDUP_ENABLED} (the capacity below it only matters when
 *       dedup is on). Adding a second aggregate over the same keys would be a
 *       second way to say "off" — the duplication this catalog avoids.</li>
 * </ol>
 *
 * <p>Consequence for readers and for the docs: the number of keys is not the
 * mental burden — the number of <em>decisions</em> is, and that is five.
 */
public final class CloudConfigKeys {
    private CloudConfigKeys() {}

    static final String PREFIX = "freeway.cloud";

    // ── Decision keys: secret store (bootstrap-only, -D ONLY) ─────────────
    // Read with System.getProperty: the secret provider is part of the symbol
    // chain, so its own configuration cannot travel through that chain.
    // FREEWAY_CLOUD_SECRET_FILE / FREEWAY_CLOUD_SECRET_KEYS do NOT work.
    public static final String SECRET_FILE        = PREFIX + ".secret.file";
    /** Optional allowlist: when set, only these symbol names resolve from the
     *  secret store (see {@code SecretSymbolSource} for why that matters). */
    public static final String SECRET_KEYS        = PREFIX + ".secret.keys";

    // ── Decision keys: a multi-node mesh needs both ─────────
    /** Static mesh endpoints to dial (host:port, see {@code PeerAddress}).
     *  Presence alone activates the mesh (unless {@code event.enabled=false}
     *  explicitly suppresses it); discovery-fed peers are additive via
     *  {@code PeerConnector.setPeers}. */
    public static final String EVENT_PEERS          = PREFIX + ".event.peers";
    /** Shared secret the mesh handshake must present; blank = no peer auth.
     *  Every node of a multi-node mesh must set the same value. */
    public static final String EVENT_TOKEN      = PREFIX + ".event.token";

    // ── Decision keys: mTLS for outbound RPC (presence-driven) ────────────
    public static final String RPC_TLS_KEY_STORE          = PREFIX + ".rpc.tls.key-store";
    public static final String RPC_TLS_KEY_STORE_PASSWORD = PREFIX + ".rpc.tls.key-store-password";

    // ── Default-optimal: backend selection (empty = built-in local) ──────
    public static final String SECRET_TYPE        = PREFIX + ".secret.type";
    public static final String STORAGE_TYPE       = PREFIX + ".storage.type";
    public static final String DISCOVERY_TYPE        = PREFIX + ".discovery.type";
    public static final String REGISTRY_TYPE         = PREFIX + ".registry.type";

    // ── Default-optimal: feature posture ────────────────────
    /** Master switch for the WS event mesh — presence-driven: an explicit
     *  value wins ({@code true} on, {@code false} = kill switch suppressing
     *  even a configured peer list); unset falls to the presence rule —
     *  configured peers imply a mesh. Unset with no peers leaves the mesh
     *  unwired: installing CloudEventModule alone stays inert. */
    public static final String EVENT_ENABLED        = PREFIX + ".event.enabled";
    public static final String RPC_TRACE_ENABLED       = PREFIX + ".rpc.trace.enabled";
    /** Off by default: dedup changes delivery semantics (an event reaching
     *  this node over two transports is delivered once) and costs memory, so
     *  it is opt-in rather than a side effect of installing a second
     *  transport. Only meaningful when inbound event carry the bus-minted
     *  wire id. */
    public static final String EVENT_DEDUP_ENABLED  = PREFIX + ".event.dedup.enabled";
    /** Off by default: inbound {@code x-principal} extraction trusts client
     *  headers and must be enabled explicitly (ideally only inside a trusted
     *  service mesh / with an ext token-verifying security module). */
    public static final String AUTH_EXTRACT_ENABLED = PREFIX + ".auth.extract.enabled";

    // ── Default-optimal: receiver routing and derived placement ──────────
    // The subscriptions/allowlists are receiver-side routing and security
    // decisions (empty allowed-types silently drops every CLASS event), and
    // the registry entries default to what the HTTP server bound.
    /** CE type/topic prefixes this node declares in its hello — peers fan out
     *  only what matches, so empty = outbound-only (nothing to receive). */
    public static final String EVENT_SUBSCRIPTIONS  = PREFIX + ".event.subscriptions";
    /** CLASS-channel deserialization allowlist; empty = deny-by-default
     *  (CLASS-channel event are dropped). */
    public static final String EVENT_ALLOWED_TYPES  = PREFIX + ".event.allowed-types";
    /** TOPIC-channel allowlist; empty = accept any topic from an admitted peer. */
    public static final String EVENT_ALLOWED_TOPICS = PREFIX + ".event.allowed-topics";
    public static final String STORAGE_BASE_PATH  = PREFIX + ".storage.base-path";
    /** Local backend root under the working directory. */
    public static final String STORAGE_BASE_PATH_DEFAULT = "cloud-storage";
    public static final String REGISTRY_SERVICE_ID   = PREFIX + ".registry.service-id";
    public static final String REGISTRY_SERVICE_HOST = PREFIX + ".registry.service-host";
    /** Scheme registered for this instance (http or https); default http. */
    public static final String REGISTRY_SERVICE_SCHEME = PREFIX + ".registry.service-scheme";
    public static final String REGISTRY_SERVICE_PORT = PREFIX + ".registry.service-port";
    public static final String REGISTRY_SERVICE_INSTANCE_ID = PREFIX + ".registry.service-instance-id";
    /** Scheme used when {@link #REGISTRY_SERVICE_SCHEME} is unset. */
    public static final String REGISTRY_SERVICE_SCHEME_DEFAULT = "http";

    // ── Advanced: timeouts (NOT governed by resilience — they always apply) ──
    // Listed first because they are the one pair in this block that
    // RPC_RESILIENCE=off does not cover.
    public static final String RPC_CONNECT_TIMEOUT     = PREFIX + ".rpc.connect-timeout";
    public static final String RPC_REQUEST_TIMEOUT     = PREFIX + ".rpc.request-timeout";

    // ── Advanced: resilience bundle, governed by RPC_RESILIENCE=auto|off ──
    /** Aggregate switch for the whole resilience bundle: {@code auto}
     *  (default — the fine-grained {@code rpc.retry.*} /
     *  {@code rpc.circuit-breaker.*} / {@code rpc.rate-limit.*} keys govern)
     *  or {@code off} (kill switch — no retry, NOOP breaker, unlimited
     *  limiter; the fine-grained keys are ignored). {@code off} is the
     *  escape hatch for mesh takeover (the platform already retries) and
     *  failure diagnosis; it must be explicit. Honored when
     *  CloudResilienceModule is installed — the CloudModule default. */
    public static final String RPC_RESILIENCE        = PREFIX + ".rpc.resilience";
    public static final String RPC_RESILIENCE_AUTO   = "auto";
    public static final String RPC_RESILIENCE_OFF    = "off";
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

    // Canonical RPC timeout defaults (ms) — shared by CloudRpcModule (config
    // fallbacks) and CloudHttpClientDefault.Wiring (library fallback when the
    // module is not installed), so the two layers cannot drift apart.
    public static final long RPC_REQUEST_TIMEOUT_DEFAULT = 10_000;
    public static final long RPC_CONNECT_TIMEOUT_DEFAULT = 3_000;

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

    // ── Advanced: RPC client trust (mTLS only, empty = plaintext) ────────
    public static final String RPC_TLS_TRUST_STORE        = PREFIX + ".rpc.tls.trust-store";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD = PREFIX + ".rpc.tls.trust-store-password";
    // Blank-string defaults: unset TLS keys mean plaintext development
    // (CloudRpcModule resolves TransportSecurity.NONE when the key store is blank).
    public static final String RPC_TLS_KEY_STORE_DEFAULT = "";
    public static final String RPC_TLS_KEY_STORE_PASSWORD_DEFAULT = "";
    public static final String RPC_TLS_TRUST_STORE_DEFAULT = "";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD_DEFAULT = "";

    // ── Advanced: mesh transport, reachable only once the mesh exists ─────
    /** How many inbound ids to remember — the window in which a second copy
     *  of an event is still recognized. Too small and a slow second copy
     *  slips through; too large and the window costs memory for nothing. */
    public static final String EVENT_DEDUP_CAPACITY = PREFIX + ".event.dedup.capacity";
    public static final int EVENT_DEDUP_CAPACITY_DEFAULT = 4096;
    public static final String EVENT_PATH_DEFAULT   = "/cloud/event";

    // Shared by the CloudEventLifecycleHook specs and PeerConnector's
    // library fallback — one value per timeout.
    /** Socket connect timeout for outbound mesh peer dials. */
    public static final String EVENT_CONNECT_TIMEOUT_MS   = PREFIX + ".event.connect-timeout-ms";
    public static final long EVENT_CONNECT_TIMEOUT_MS_DEFAULT   = 3000;
    /** A peer that accepts the socket but never answers the hello must not pin
     *  a half-open connection forever. */
    public static final String EVENT_HANDSHAKE_TIMEOUT_MS = PREFIX + ".event.handshake-timeout-ms";
    public static final long EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT = 10000;
    /** Reconnect backoff floor / ceiling (exponential, capped). */
    public static final String EVENT_BACKOFF_BASE_MS = PREFIX + ".event.backoff-base-ms";
    public static final long EVENT_BACKOFF_BASE_MS_DEFAULT = 1000;
    public static final String EVENT_BACKOFF_MAX_MS  = PREFIX + ".event.backoff-max-ms";
    public static final long EVENT_BACKOFF_MAX_MS_DEFAULT  = 30000;
}
