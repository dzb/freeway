package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.ioc.OptionalBinding;
import com.jujin.freeway.cloud.context.ContextCodec;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.RetryBudget;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;


/**
 * IoC wiring for remote invocation: {@link RpcHttpClient} →
 * {@link RpcHttpClientDefault} (JDK {@code HttpClient}, {@code @Local}
 * marker). Timeouts resolve from
 * {@code freeway.cloud.rpc.connect-timeout} / {@code request-timeout};
 * resilience policies come from {@code ResilienceModule} when installed
 * (otherwise built-in defaults). Transport security defaults to plaintext
 * development; keystore config ({@code freeway.cloud.rpc.tls.*}) builds
 * file-backed mTLS. When {@code ObserveModule} is installed, RPC calls
 * are traced ({@code freeway.cloud.rpc.trace.enabled}, default on) and
 * counted against {@code cloud.rpc.*} metrics. Explicit HTTP calls only —
 * no method-level RPC.
 */
@Marker(Builtin.class)
public final class RpcModule implements ModuleEx {

    // Key, type and default declared once per key; the symbol chain resolves
    // the raw value and the spec post-processes it. Defaults come from the
    // shared ConfigKeys sources so the config layer and the library
    // fallback (RpcHttpClientDefault.Wiring) cannot drift apart.
    private static final SymbolSpec<Long> REQUEST_TIMEOUT_MS = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_REQUEST_TIMEOUT, Long.class,
        RpcModule.ConfigKeys.RPC_REQUEST_TIMEOUT_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> CONNECT_TIMEOUT_MS = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_CONNECT_TIMEOUT, Long.class,
        RpcModule.ConfigKeys.RPC_CONNECT_TIMEOUT_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Boolean> TRACE_ENABLED = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_TRACE_ENABLED, Boolean.class, true);

    /** Shutdown drain for in-flight calls, from the shared default. */
    private static final SymbolSpec<Duration> SHUTDOWN_GRACE = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_SHUTDOWN_GRACE, Duration.class,
        RpcModule.ConfigKeys.RPC_SHUTDOWN_GRACE_DEFAULT);

    // TLS stores: unset (blank) keys mean plaintext development — the module
    // resolves TransportSecurity.NONE when the key store is blank.
    private static final SymbolSpec<String> TLS_KEY_STORE = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_TLS_KEY_STORE, String.class,
        RpcModule.ConfigKeys.RPC_TLS_KEY_STORE_DEFAULT, Function.identity());
    private static final SymbolSpec<String> TLS_KEY_STORE_PASSWORD = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_TLS_KEY_STORE_PASSWORD, String.class,
        RpcModule.ConfigKeys.RPC_TLS_KEY_STORE_PASSWORD_DEFAULT, Function.identity());
    private static final SymbolSpec<String> TLS_TRUST_STORE = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_TLS_TRUST_STORE, String.class,
        RpcModule.ConfigKeys.RPC_TLS_TRUST_STORE_DEFAULT, Function.identity());
    private static final SymbolSpec<String> TLS_TRUST_STORE_PASSWORD = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_TLS_TRUST_STORE_PASSWORD, String.class,
        RpcModule.ConfigKeys.RPC_TLS_TRUST_STORE_PASSWORD_DEFAULT, Function.identity());
    /** Presence-driven, like the inbound side's reload key: unset keeps the
     *  resolved-once behaviour. */
    private static final SymbolSpec<Duration> TLS_RELOAD_INTERVAL = SymbolSpec.of(
        RpcModule.ConfigKeys.RPC_TLS_RELOAD_INTERVAL, Duration.class,
        RpcModule.ConfigKeys.RPC_TLS_RELOAD_INTERVAL_DEFAULT);

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX))
            // The no-discovery fallback still honors the shared zone policy.
            .add(KnownKeys.of(LoadBalancer.ConfigKeys.class, CloudKeys.PREFIX));
        b.bind(TransportSecurity.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                String keyStore = symbols.resolve(TLS_KEY_STORE);
                if (keyStore.isBlank()) {
                    return TransportSecurity.NONE; // plaintext development default
                }
                String keyPassword = symbols.resolve(TLS_KEY_STORE_PASSWORD);
                String trustStore = symbols.resolve(TLS_TRUST_STORE);
                String trustPassword = symbols.resolve(TLS_TRUST_STORE_PASSWORD);
                Duration reload = symbols.resolve(TLS_RELOAD_INTERVAL);
                return TransportSecurityDefault.fromKeyStore(
                    Path.of(keyStore), keyPassword,
                    trustStore.isBlank() ? null : Path.of(trustStore), trustPassword,
                    reload);
            })
            .marker(Local.class)   // the file-backed default an ext mTLS source replaces
            ;

        b.bind(RpcHttpClient.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                long requestMs = symbols.resolve(REQUEST_TIMEOUT_MS);
                long connectMs = symbols.resolve(CONNECT_TIMEOUT_MS);
                Duration shutdownGrace = symbols.resolve(SHUTDOWN_GRACE);
                boolean traceEnabled = symbols.resolve(TRACE_ENABLED);
                // Discovery is a deployment choice: CloudModule no longer
                // installs a discovery backend by default (static/Fleet/Consul
                // are explicit). RPC still assembles without one; a call then
                // fails with "no instances" instead of preventing the bundle
                // from starting. A bound discovery/LoadBalancer wins.
                ServiceDiscovery discovery =
                    OptionalBinding.get(container, ServiceDiscovery.class);
                if (discovery == null) {
                    discovery = serviceId -> List.of();
                }
                LoadBalancer loadBalancer =
                    OptionalBinding.get(container, LoadBalancer.class);
                if (loadBalancer == null) {
                    loadBalancer = new LoadBalancerDefault(
                        symbols.resolve(LoadBalancer.ConfigKeys.LOCAL_ZONE, ""));
                }
                return new RpcHttpClientDefault(
                    discovery,
                    loadBalancer,
                    new RpcHttpClientDefault.Wiring(
                        container.extension(ContextCodec.class).all(),
                        OptionalBinding.get(container, Retryer.class),
                        OptionalBinding.get(container, CircuitBreaker.class),
                        OptionalBinding.get(container, RateLimiter.class),
                        container.get(TransportSecurity.class),
                        traceEnabled ? OptionalBinding.get(container, Tracer.class) : null,
                        // Metrics has a NoopMetrics builtin — always resolvable;
                        // without ObserveModule calls record into the noop.
                        container.get(Metrics.class),
                        Duration.ofMillis(requestMs),
                        Duration.ofMillis(connectMs),
                        shutdownGrace),
                    OptionalBinding.get(container, RetryBudget.class));
            })
            .marker(Local.class)
            ;

        // The consumer side needs one hand-wired object fewer: the caller is a
        // plain composition of two builtins, so the framework assembles it and
        // an application injects it instead of building it.
        b.bind(RemoteCaller.class)
            .to((Container container) -> new RemoteCaller(
                container.get(RpcHttpClient.class),
                container.get(JsonCodec.class)));

        // The server side is one wildcard route plus the startup hook that
        // resolves the declared exports: the route is contributed here (bind
        // time, the designed write path) so nothing depends on hook ordering,
        // and the hook fills the state the route needs before the server starts.
        var exportHook = new RpcExportHook();
        b.contribute(Route.class)
            .add("freeway.cloud.rpc", RpcEndpoint.exportsRoute(exportHook));
        b.contribute(RuntimeHook.class)
            .add(CloudHookIds.RPC, exportHook)
            // Conditional: a pure RPC client has no server to precede.
            .beforeIfPresent(CloudHookIds.HTTP_SERVER);
    }


    /** Keys read by the RPC client module. */
    public static final class ConfigKeys {
        private ConfigKeys() {}
        public static final String RPC_TLS_KEY_STORE = "freeway.cloud.rpc.tls.key-store";
        public static final String RPC_TLS_KEY_STORE_PASSWORD = "freeway.cloud.rpc.tls.key-store-password";
        public static final String RPC_TLS_KEY_STORE_DEFAULT = "";
        public static final String RPC_TLS_KEY_STORE_PASSWORD_DEFAULT = "";
        public static final String RPC_TLS_TRUST_STORE = "freeway.cloud.rpc.tls.trust-store";
        public static final String RPC_TLS_TRUST_STORE_PASSWORD = "freeway.cloud.rpc.tls.trust-store-password";
        public static final String RPC_TLS_TRUST_STORE_DEFAULT = "";
        public static final String RPC_TLS_TRUST_STORE_PASSWORD_DEFAULT = "";
        public static final String RPC_TLS_RELOAD_INTERVAL = "freeway.cloud.rpc.tls.reload-interval";
        public static final java.time.Duration RPC_TLS_RELOAD_INTERVAL_DEFAULT = java.time.Duration.ZERO;
        public static final String RPC_TRACE_ENABLED = "freeway.cloud.rpc.trace.enabled";
        public static final String RPC_CONNECT_TIMEOUT = "freeway.cloud.rpc.connect-timeout";
        public static final long RPC_CONNECT_TIMEOUT_DEFAULT = 3000;
        public static final String RPC_REQUEST_TIMEOUT = "freeway.cloud.rpc.request-timeout";
        public static final long RPC_REQUEST_TIMEOUT_DEFAULT = 10_000;
        public static final String RPC_SHUTDOWN_GRACE = "freeway.cloud.rpc.shutdown-grace";
        public static final java.time.Duration RPC_SHUTDOWN_GRACE_DEFAULT = java.time.Duration.ofSeconds(5);
    }
}
