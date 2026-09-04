package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.time.Duration;

/**
 * IoC wiring for resilience: {@link Retryer} / {@link CircuitBreaker} /
 * {@link RateLimiter} defaults, configured from {@code freeway.cloud.rpc.*}
 * keys ({@code @Local} marker). The {@code CloudHttpClient}
 * layer applies them uniformly; Advisor-based weaving for local services is a
 * later, optional addition.
 */
@Marker(Builtin.class)
public final class CloudResilienceModule implements ModuleEx {

    // Key, type and default declared once per key; the symbol chain resolves
    // the raw value and the spec post-processes it. Defaults come from the
    // shared CloudConfigKeys sources so the config layer and the library
    // fallback (CloudHttpClientDefault) cannot drift apart.
    private static final SymbolSpec<Integer> RETRY_MAX_ATTEMPTS = SymbolSpec.of(
        CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, Integer.class,
        CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Long> RETRY_BACKOFF_BASE = SymbolSpec.of(
        CloudConfigKeys.RPC_RETRY_BACKOFF_BASE, Long.class,
        CloudConfigKeys.RPC_RETRY_BACKOFF_BASE_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> RETRY_BACKOFF_MAX = SymbolSpec.of(
        CloudConfigKeys.RPC_RETRY_BACKOFF_MAX, Long.class,
        CloudConfigKeys.RPC_RETRY_BACKOFF_MAX_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Boolean> CB_ENABLED = SymbolSpec.of(
        CloudConfigKeys.RPC_CB_ENABLED, Boolean.class, true, Boolean::parseBoolean);
    private static final SymbolSpec<Integer> CB_FAILURE_THRESHOLD = SymbolSpec.of(
        CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD, Integer.class,
        CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Long> CB_FAILURE_WINDOW = SymbolSpec.of(
        CloudConfigKeys.RPC_CB_FAILURE_WINDOW, Long.class,
        CloudConfigKeys.RPC_CB_FAILURE_WINDOW_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> CB_OPEN_WINDOW = SymbolSpec.of(
        CloudConfigKeys.RPC_CB_OPEN_WINDOW, Long.class,
        CloudConfigKeys.RPC_CB_OPEN_WINDOW_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Boolean> RATE_LIMIT_ENABLED = SymbolSpec.of(
        CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, Boolean.class, false, Boolean::parseBoolean);
    private static final SymbolSpec<Double> RATE_LIMIT_PER_SECOND = SymbolSpec.of(
        CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND, Double.class,
        CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND_DEFAULT, Double::parseDouble);

    @Override
    public void bind(Binder b) {
        b.bind(Retryer.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                return new RetryerDefault(
                    symbols.resolve(RETRY_MAX_ATTEMPTS),
                    symbols.resolve(RETRY_BACKOFF_BASE),
                    symbols.resolve(RETRY_BACKOFF_MAX));
            })
            .marker(Local.class)
            ;

        b.bind(CircuitBreaker.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                if (!symbols.resolve(CB_ENABLED)) {
                    return CircuitBreaker.NOOP;
                }
                return new CircuitBreakerDefault(
                    symbols.resolve(CB_FAILURE_THRESHOLD),
                    Duration.ofSeconds(symbols.resolve(CB_FAILURE_WINDOW)),
                    Duration.ofSeconds(symbols.resolve(CB_OPEN_WINDOW)));
            })
            .marker(Local.class)
            ;

        b.bind(RateLimiter.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                return symbols.resolve(RATE_LIMIT_ENABLED)
                    ? new RateLimiterDefault(symbols.resolve(RATE_LIMIT_PER_SECOND))
                    : RateLimiter.UNLIMITED;
            })
            .marker(Local.class)
            ;
    }
}
