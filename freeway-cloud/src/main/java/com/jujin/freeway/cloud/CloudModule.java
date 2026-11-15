package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.health.HealthModule;
import com.jujin.freeway.cloud.observe.ObserveModule;
import com.jujin.freeway.cloud.resilience.ResilienceModule;
import com.jujin.freeway.cloud.rpc.RpcModule;
import com.jujin.freeway.cloud.secret.SecretModule;
import com.jujin.freeway.cloud.storage.StorageModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.annotation.SubModule;
import java.time.Duration;

/**
 * The standard cloud bundle: the seven {@code freeway-cloud} modules declared
 * as one. Placing this module places them — pre-order, after the module
 * itself — and the startup tree shows the bundle as its own level:
 *
 * <pre>{@code
 * FreewayApp.create(new OrderModule()).add(CloudModule.class).start();  // the whole bundle
 * }</pre>
 *
 * <p>Any module below is an ordinary module, so taking a subset is placing it
 * directly instead of the bundle — no exclusion list exists. A subset still
 * has to include what the subset reads: the RPC client resolves through
 * {@code ServiceDiscovery} and {@code LoadBalancer}, which
 * {@code freeway-cloud} does not bind by default, so "just the client" is two modules —
 * otherwise the container builds and the first call fails with a missing
 * binding:
 *
 * <pre>{@code
 * FreewayApp.run(new OrderModule(),
 *     RpcModule.class, StaticDiscoveryModule.class);   // just the client
 * }</pre>
 *
 * <p>{@code bind(Binder)} is the shared cloud surface: cross-cutting bindings
 * or contributions that belong to the bundle as a whole (the modules
 * themselves are declared by {@link SubModule}, not installed by this method).
 *
 * <p>{@link com.jujin.freeway.cloud.event.EventMeshModule} is intentionally
 * absent: it opens listeners and dials peers, so a mesh is something an
 * application asks for explicitly.
 */
@SubModule({
    CloudContextModule.class,
    SecretModule.class,
    RpcModule.class,
    ObserveModule.class,
    ResilienceModule.class,
    HealthModule.class,
    StorageModule.class
})
/**
 * The standard cloud bundle: declares the context, secret, RPC, observe,
 * resilience, health and storage modules as submodules, so placing this
 * class places all of them. Each is an ordinary module and can be placed on its
 * own instead — the bundle is a convenience, not a policy.
 */
public final class CloudModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        // Shared cloud bindings would live here. The standard modules arrive
        // through the @SubModule declaration above, not through this method:
        // composition is data, not a binding side effect.
        //
        // The one thing that does belong here is the plane's own vocabulary: the key table is a
        // nested type of this class, and naming both namespaces it spells keeps a table key from
        // sitting outside the unknown-key check (KnownKeys.of fails the bind instead of dropping it).
        binder.contribute(KnownKeys.class).add(
            KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX, ConfigKeys.APP_PREFIX));
    }


    /**
     * Central config keys and canonical defaults for {@code freeway-cloud}: both namespaces it
     * spells ({@code PREFIX} / {@code APP_PREFIX}), every key written as a full literal. How the
     * surface is meant to be read, and which keys a deployment must decide:
     * {@code docs/design/cloud.md} §6.
     */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        public static final String PREFIX = "freeway.cloud";

        /** The service-name fallback namespace, owned here because cloud registration is its only
         *  reader: a second namespace of this module's surface, not a second table. */
        public static final String APP_PREFIX = "freeway.app";

        // ── Decision keys: secret store (bootstrap-only, -D ONLY) ─────────────
        // Read with System.getProperty: the secret provider is part of the symbol
        // chain, so its own configuration cannot travel through that chain.
        // FREEWAY_CLOUD_SECRET_FILE / FREEWAY_CLOUD_SECRET_KEYS do NOT work.
        public static final String SECRET_FILE        = "freeway.cloud.secret.file";
        /** Optional allowlist: when set, only these symbol names resolve from the
         *  secret store (see {@code SecretSymbolSource} for why that matters). */
        public static final String SECRET_KEYS        = "freeway.cloud.secret.keys";

        // ── Decision keys: a multi-node mesh needs both ─────────
        /** Static mesh endpoints to dial (host:port, see {@code PeerAddress}).
         *  Presence alone activates the mesh (unless {@code event.enabled=false}
         *  explicitly suppresses it); discovery-fed peers are a second, replaceable
         *  set, polled from {@code ServiceDiscovery} while
         *  {@code discovery-feed-ms} is set. */
        public static final String EVENT_PEERS          = "freeway.cloud.event.peers";
        /** Poll interval (ms) for the discovery-fed mesh: while set, the event module
         *  periodically asks the bound {@code ServiceDiscovery} for instances of this
         *  node's own service and replaces the mesh's discovered peer set with what
         *  it finds (this node excluded). Unset = off; a value below 1 fails startup.
         *  Static {@code event.peers} are a separate set and survive an empty round. */
        public static final String EVENT_DISCOVERY_FEED_MS = "freeway.cloud.event.discovery-feed-ms";
        /** Keepalive probe interval (ms) for mesh connections: a tick pings
         *  the peer, one probe is in flight at a time, and its pong budget is
         *  {@code max(3 × interval, 1 s)} — so a pause-sized stall does not
         *  close a healthy link. Catches half-open links a NAT/LB drops
         *  silently. Unset = off (TCP keepalive and send failures are the only
         *  signals then). */
        public static final String EVENT_KEEPALIVE_MS = "freeway.cloud.event.keepalive-ms";
        /** Shared secret the mesh handshake must present; blank = no peer auth.
         *  Every node of a multi-node mesh must set the same value. */
        public static final String EVENT_TOKEN      = "freeway.cloud.event.token";

        // ── Decision keys: mTLS for outbound RPC (presence-driven) ────────────
        public static final String RPC_TLS_KEY_STORE          = "freeway.cloud.rpc.tls.key-store";
        public static final String RPC_TLS_KEY_STORE_PASSWORD = "freeway.cloud.rpc.tls.key-store-password";

        // ── Default-optimal: backend selection (empty = built-in local) ──────
        public static final String SECRET_TYPE        = "freeway.cloud.secret.type";
        public static final String STORAGE_TYPE       = "freeway.cloud.storage.type";
        /** This process's zone; blank = no zone preference. */

        // ── Default-optimal: feature posture ────────────────────
        /** Master switch for the WS event mesh — presence-driven: an explicit
         *  value wins ({@code true} on, {@code false} = kill switch suppressing
         *  even a configured peer list); unset falls to the presence rule —
         *  configured peers imply a mesh. Unset with no peers leaves the mesh
         *  unwired: installing EventMeshModule alone stays inert. */
        public static final String EVENT_ENABLED        = "freeway.cloud.event.enabled";
        public static final String RPC_TRACE_ENABLED       = "freeway.cloud.rpc.trace.enabled";
        /** Off by default: inbound {@code x-principal} extraction trusts client
         *  headers and must be enabled explicitly (ideally only inside a trusted
         *  service mesh / with an ext token-verifying security module). */
        public static final String AUTH_EXTRACT_ENABLED = "freeway.cloud.auth.extract.enabled";

        // ── Default-optimal: derived placement ────────────────────────────────
        public static final String STORAGE_BASE_PATH  = "freeway.cloud.storage.base-path";
        /** Local backend root under the working directory. */
        public static final String STORAGE_BASE_PATH_DEFAULT = "cloud-storage";
        /**
         * Generic service-name fallback used by registration and the event
         * mesh ({@code freeway.cloud.registry.service-id} unset → this key →
         * {@link #APP_NAME_DEFAULT}). Outside {@link #PREFIX} on purpose; it is
         * distinct from JVM {@code -D app.name} (log file name).
         */
        public static final String APP_NAME = "freeway.app.name";
        /** Value used when neither the registration service-id nor {@link #APP_NAME} is set. */
        public static final String APP_NAME_DEFAULT = "freeway-app";
        // ── Advanced: timeouts (NOT governed by resilience — they always apply) ──
        // Listed first because they are the one pair in this block that
        // RPC_RESILIENCE=off does not cover.
        public static final String RPC_CONNECT_TIMEOUT     = "freeway.cloud.rpc.connect-timeout";
        public static final String RPC_REQUEST_TIMEOUT     = "freeway.cloud.rpc.request-timeout";

        // ── Advanced: resilience bundle, governed by RPC_RESILIENCE=auto|off ──
        /** Aggregate switch for the whole resilience bundle: {@code auto}
         *  (default — the fine-grained {@code rpc.retry.*} /
         *  {@code rpc.circuit-breaker.*} / {@code rpc.rate-limit.*} keys govern)
         *  or {@code off} (kill switch — no retry, NOOP breaker, unlimited
         *  limiter; the fine-grained keys are ignored). {@code off} is the
         *  escape hatch for mesh takeover (the platform already retries) and
         *  failure diagnosis; it must be explicit. Honored when
         *  ResilienceModule is installed — the CloudModule bundle default. */
        public static final String RPC_RESILIENCE        = "freeway.cloud.rpc.resilience";
        public static final String RPC_RESILIENCE_AUTO   = "auto";
        public static final String RPC_RESILIENCE_OFF    = "off";
        public static final String RPC_RETRY_MAX_ATTEMPTS  = "freeway.cloud.rpc.retry.max-attempts";
        public static final String RPC_RETRY_BACKOFF_BASE  = "freeway.cloud.rpc.retry.backoff-base";
        public static final String RPC_RETRY_BACKOFF_MAX   = "freeway.cloud.rpc.retry.backoff-max";
        /** Per-service retry tokens: successful calls replenish, retries consume, 0 = unlimited. */
        public static final String RPC_RETRY_BUDGET        = "freeway.cloud.rpc.retry.budget";
        public static final String RPC_CB_ENABLED          = "freeway.cloud.rpc.circuit-breaker.enabled";
        public static final String RPC_CB_FAILURE_THRESHOLD = "freeway.cloud.rpc.circuit-breaker.failure-threshold";
        /** Seconds a failure stays in the sliding window before it drops out of the count. */
        public static final String RPC_CB_FAILURE_WINDOW   = "freeway.cloud.rpc.circuit-breaker.failure-window";
        public static final String RPC_CB_OPEN_WINDOW      = "freeway.cloud.rpc.circuit-breaker.open-window";
        public static final String RPC_RATE_LIMIT_ENABLED  = "freeway.cloud.rpc.rate-limit.enabled";
        public static final String RPC_RATE_LIMIT_PER_SECOND = "freeway.cloud.rpc.rate-limit.per-second";

        // Canonical RPC timeout defaults (ms) — shared by RpcModule (config
        // fallbacks) and RpcHttpClientDefault.Wiring (library fallback when the
        // module is not installed), so the two layers cannot drift apart.
        public static final long RPC_REQUEST_TIMEOUT_DEFAULT = 10_000;
        /**
         * How long {@code RpcHttpClient.close()} waits for calls already in
         * flight before failing them. Cutting a call the server may already have
         * applied is worse than a slower stop, so the default waits a few seconds —
         * an idle process still closes immediately (nothing in flight, nothing to
         * wait for). Bounded by the deployment's termination grace period.
         */
        public static final String RPC_SHUTDOWN_GRACE = "freeway.cloud.rpc.shutdown-grace";
        /** Library default for {@link #RPC_SHUTDOWN_GRACE}. */
        public static final Duration RPC_SHUTDOWN_GRACE_DEFAULT = Duration.ofSeconds(5);
        public static final long RPC_CONNECT_TIMEOUT_DEFAULT = 3_000;

        // Canonical retry/breaker defaults — shared by ResilienceModule
        // (config fallbacks) and RpcHttpClientDefault (library fallback when
        // the resilience module is not installed), so the two layers cannot
        // drift apart. Rate limiting itself defaults to disabled and uses
        // RateLimiter.UNLIMITED on both paths; the per-second default below only
        // feeds ResilienceModule when rate limiting is enabled.
        public static final int RPC_RETRY_MAX_ATTEMPTS_DEFAULT    = 3;
        public static final long RPC_RETRY_BACKOFF_BASE_DEFAULT   = 100;
        public static final long RPC_RETRY_BACKOFF_MAX_DEFAULT    = 5000;
        public static final int RPC_RETRY_BUDGET_DEFAULT          = 20;
        public static final int RPC_CB_FAILURE_THRESHOLD_DEFAULT  = 5;
        public static final long RPC_CB_FAILURE_WINDOW_DEFAULT    = 60;
        public static final long RPC_CB_OPEN_WINDOW_DEFAULT       = 30;
        public static final double RPC_RATE_LIMIT_PER_SECOND_DEFAULT = 100;

        // ── Advanced: RPC client trust (mTLS only, empty = plaintext) ────────
        public static final String RPC_TLS_TRUST_STORE        = "freeway.cloud.rpc.tls.trust-store";
        public static final String RPC_TLS_TRUST_STORE_PASSWORD = "freeway.cloud.rpc.tls.trust-store-password";
        /** How often rotated outbound TLS material may be picked up (unset/0 =
         *  resolved once, a rotated keystore needs a restart). Set it for
         *  short-lived certificates: the key/trust managers re-read the files
         *  per handshake instead of the context being rebuilt, because the JDK
         *  {@code HttpClient} captures its context at construction. */
        public static final String RPC_TLS_RELOAD_INTERVAL = "freeway.cloud.rpc.tls.reload-interval";
        public static final Duration RPC_TLS_RELOAD_INTERVAL_DEFAULT = Duration.ZERO;
        // Blank-string defaults: unset TLS keys mean plaintext development
        // (RpcModule resolves TransportSecurity.NONE when the key store is blank).
        public static final String RPC_TLS_KEY_STORE_DEFAULT = "";
        public static final String RPC_TLS_KEY_STORE_PASSWORD_DEFAULT = "";
        public static final String RPC_TLS_TRUST_STORE_DEFAULT = "";
        public static final String RPC_TLS_TRUST_STORE_PASSWORD_DEFAULT = "";

        // ── Advanced: mesh transport, reachable only once the mesh exists ─────

        // Shared by the MeshEventLifecycleHook specs and PeerConnector's
        // library fallback — one value per timeout.
        /** Socket connect timeout for outbound mesh peer dials. */
        public static final String EVENT_CONNECT_TIMEOUT_MS   = "freeway.cloud.event.connect-timeout-ms";
        public static final long EVENT_CONNECT_TIMEOUT_MS_DEFAULT   = 3000;
        /** A peer that accepts the socket but never answers the hello must not pin
         *  a half-open connection forever. */
        public static final String EVENT_HANDSHAKE_TIMEOUT_MS = "freeway.cloud.event.handshake-timeout-ms";
        public static final long EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT = 10000;
        /** Reconnect backoff floor / ceiling (exponential, capped). */
        public static final String EVENT_BACKOFF_BASE_MS = "freeway.cloud.event.backoff-base-ms";
        public static final long EVENT_BACKOFF_BASE_MS_DEFAULT = 1000;
        public static final String EVENT_BACKOFF_MAX_MS  = "freeway.cloud.event.backoff-max-ms";
        public static final long EVENT_BACKOFF_MAX_MS_DEFAULT  = 30000;
    }
}
