package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.internal.TransportSecurityImpl;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

/**
 * IoC wiring for remote invocation: {@link CloudHttpClient} →
 * {@link CloudHttpClientDefault} (JDK {@code HttpClient}, {@code @Local}
 * marker). Timeouts resolve from
 * {@code freeway.cloud.rpc.connect-timeout} / {@code request-timeout};
 * resilience policies come from {@code CloudResilienceModule} when installed
 * (otherwise built-in defaults). Transport security defaults to plaintext
 * development; keystore config ({@code freeway.cloud.rpc.tls.*}) builds
 * file-backed mTLS. When {@code CloudObserveModule} is installed, RPC calls
 * are traced ({@code freeway.cloud.rpc.trace.enabled}, default on) and
 * counted against {@code cloud.rpc.*} metrics. Explicit HTTP calls only —
 * no method-level RPC.
 */
@Marker(Builtin.class)
public final class CloudRpcModule implements ModuleEx {

    // Key, type and default declared once per key; the symbol chain resolves
    // the raw value and the spec post-processes it. Defaults come from the
    // shared CloudConfigKeys sources so the config layer and the library
    // fallback (CloudHttpClientDefault.Wiring) cannot drift apart.
    private static final SymbolSpec<Long> REQUEST_TIMEOUT_MS = SymbolSpec.of(
        CloudConfigKeys.RPC_REQUEST_TIMEOUT, Long.class,
        CloudConfigKeys.RPC_REQUEST_TIMEOUT_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> CONNECT_TIMEOUT_MS = SymbolSpec.of(
        CloudConfigKeys.RPC_CONNECT_TIMEOUT, Long.class,
        CloudConfigKeys.RPC_CONNECT_TIMEOUT_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Boolean> TRACE_ENABLED = SymbolSpec.of(
        CloudConfigKeys.RPC_TRACE_ENABLED, Boolean.class, true, Boolean::parseBoolean);

    // TLS stores: unset (blank) keys mean plaintext development — the module
    // resolves TransportSecurity.NONE when the key store is blank.
    private static final SymbolSpec<String> TLS_KEY_STORE = SymbolSpec.of(
        CloudConfigKeys.RPC_TLS_KEY_STORE, String.class,
        CloudConfigKeys.RPC_TLS_KEY_STORE_DEFAULT, Function.identity());
    private static final SymbolSpec<String> TLS_KEY_STORE_PASSWORD = SymbolSpec.of(
        CloudConfigKeys.RPC_TLS_KEY_STORE_PASSWORD, String.class,
        CloudConfigKeys.RPC_TLS_KEY_STORE_PASSWORD_DEFAULT, Function.identity());
    private static final SymbolSpec<String> TLS_TRUST_STORE = SymbolSpec.of(
        CloudConfigKeys.RPC_TLS_TRUST_STORE, String.class,
        CloudConfigKeys.RPC_TLS_TRUST_STORE_DEFAULT, Function.identity());
    private static final SymbolSpec<String> TLS_TRUST_STORE_PASSWORD = SymbolSpec.of(
        CloudConfigKeys.RPC_TLS_TRUST_STORE_PASSWORD, String.class,
        CloudConfigKeys.RPC_TLS_TRUST_STORE_PASSWORD_DEFAULT, Function.identity());

    @Override
    public void bind(Binder b) {
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
                return TransportSecurityImpl.fromKeyStore(
                    Path.of(keyStore), keyPassword,
                    trustStore.isBlank() ? null : Path.of(trustStore), trustPassword);
            })
            ;

        b.bind(CloudHttpClient.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                long requestMs = symbols.resolve(REQUEST_TIMEOUT_MS);
                long connectMs = symbols.resolve(CONNECT_TIMEOUT_MS);
                boolean traceEnabled = symbols.resolve(TRACE_ENABLED);
                return new CloudHttpClientDefault(
                    container.get(ServiceDiscovery.class),
                    container.get(LoadBalancer.class),
                    new CloudHttpClientDefault.Wiring(
                        container.extension(Propagator.class).all(),
                        optional(container, Retryer.class),
                        optional(container, CircuitBreaker.class),
                        optional(container, RateLimiter.class),
                        container.get(TransportSecurity.class),
                        traceEnabled ? optional(container, Tracer.class) : null,
                        // Metrics has a NoopMetrics builtin — always resolvable;
                        // without CloudObserveModule calls record into the noop.
                        container.get(Metrics.class),
                        Duration.ofMillis(requestMs),
                        Duration.ofMillis(connectMs)));
            })
            .marker(Local.class)
            ;
    }

    private static <T> T optional(Container container, Class<T> type) {
        try {
            return container.get(type);
        } catch (MissingBindingException e) {
            return null; // resilience module not installed — client uses defaults
        }
        // Any other failure (invalid configuration, constructor error) is
        // a real problem — fail fast instead of silently degrading.
    }
}
