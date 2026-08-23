package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.internal.CloudHttpClientDefault;
import com.jujin.freeway.cloud.internal.TransportSecurityDefault;
import com.jujin.freeway.cloud.observe.MeterRegistry;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Path;
import java.time.Duration;

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

    @Override
    public void bind(Binder b) {
        b.bind(TransportSecurity.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                String keyStore = symbols.resolve(CloudConfigKeys.RPC_TLS_KEY_STORE, "");
                if (keyStore.isBlank()) {
                    return TransportSecurity.NONE; // plaintext development default
                }
                String keyPassword = symbols.resolve(CloudConfigKeys.RPC_TLS_KEY_STORE_PASSWORD, "");
                String trustStore = symbols.resolve(CloudConfigKeys.RPC_TLS_TRUST_STORE, "");
                String trustPassword = symbols.resolve(CloudConfigKeys.RPC_TLS_TRUST_STORE_PASSWORD, "");
                return TransportSecurityDefault.fromKeyStore(
                    Path.of(keyStore), keyPassword,
                    trustStore.isBlank() ? null : Path.of(trustStore), trustPassword);
            })
            ;

        b.bind(CloudHttpClient.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                long requestMs = Long.parseLong(symbols.resolve(CloudConfigKeys.RPC_REQUEST_TIMEOUT, "10000"));
                long connectMs = Long.parseLong(symbols.resolve(CloudConfigKeys.RPC_CONNECT_TIMEOUT, "3000"));
                boolean traceEnabled = Boolean.parseBoolean(
                    symbols.resolve(CloudConfigKeys.RPC_TRACE_ENABLED, "true"));
                return new CloudHttpClientDefault(
                    container.get(ServiceDiscovery.class),
                    container.get(LoadBalancer.class),
                    container.extension(Propagator.class).all(),
                    optional(container, Retryer.class),
                    optional(container, CircuitBreaker.class),
                    optional(container, RateLimiter.class),
                    container.get(TransportSecurity.class),
                    traceEnabled ? optional(container, Tracer.class) : null,
                    optional(container, MeterRegistry.class),
                    Duration.ofMillis(requestMs),
                    Duration.ofMillis(connectMs));
            })
            .marker(Local.class)
            ;
    }

    private static <T> T optional(Container container, Class<T> type) {
        try {
            return container.get(type);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null
                    && e.getMessage().startsWith("No service registered")) {
                return null; // resilience module not installed — client uses defaults
            }
            // Any other failure (invalid configuration, constructor error) is
            // a real problem — fail fast instead of silently degrading.
            throw e;
        }
    }
}
