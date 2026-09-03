package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.CircuitBreakerDefault;
import com.jujin.freeway.cloud.internal.RateLimiterDefault;
import com.jujin.freeway.cloud.internal.RetryerDefault;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
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
    private static final ConfigSpec<Integer> RETRY_MAX_ATTEMPTS = ConfigSpec.of(
        CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, Integer.class,
        CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS_DEFAULT, Integer::parseInt);
    private static final ConfigSpec<Long> RETRY_BACKOFF_BASE = ConfigSpec.of(
        CloudConfigKeys.RPC_RETRY_BACKOFF_BASE, Long.class,
        CloudConfigKeys.RPC_RETRY_BACKOFF_BASE_DEFAULT, Long::parseLong);
    private static final ConfigSpec<Long> RETRY_BACKOFF_MAX = ConfigSpec.of(
        CloudConfigKeys.RPC_RETRY_BACKOFF_MAX, Long.class,
        CloudConfigKeys.RPC_RETRY_BACKOFF_MAX_DEFAULT, Long::parseLong);
    private static final ConfigSpec<Boolean> CB_ENABLED = ConfigSpec.of(
        CloudConfigKeys.RPC_CB_ENABLED, Boolean.class, true, Boolean::parseBoolean);
    private static final ConfigSpec<Integer> CB_FAILURE_THRESHOLD = ConfigSpec.of(
        CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD, Integer.class,
        CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD_DEFAULT, Integer::parseInt);
    private static final ConfigSpec<Long> CB_FAILURE_WINDOW = ConfigSpec.of(
        CloudConfigKeys.RPC_CB_FAILURE_WINDOW, Long.class,
        CloudConfigKeys.RPC_CB_FAILURE_WINDOW_DEFAULT, Long::parseLong);
    private static final ConfigSpec<Long> CB_OPEN_WINDOW = ConfigSpec.of(
        CloudConfigKeys.RPC_CB_OPEN_WINDOW, Long.class,
        CloudConfigKeys.RPC_CB_OPEN_WINDOW_DEFAULT, Long::parseLong);
    private static final ConfigSpec<Boolean> RATE_LIMIT_ENABLED = ConfigSpec.of(
        CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, Boolean.class, false, Boolean::parseBoolean);
    private static final ConfigSpec<Double> RATE_LIMIT_PER_SECOND = ConfigSpec.of(
        CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND, Double.class,
        CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND_DEFAULT, Double::parseDouble);

    @Override
    public void bind(Binder b) {
        b.bind(Retryer.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                return new RetryerDefault(
                    RETRY_MAX_ATTEMPTS.parse(symbols.resolve(RETRY_MAX_ATTEMPTS.key(), null)),
                    RETRY_BACKOFF_BASE.parse(symbols.resolve(RETRY_BACKOFF_BASE.key(), null)),
                    RETRY_BACKOFF_MAX.parse(symbols.resolve(RETRY_BACKOFF_MAX.key(), null)));
            })
            .marker(Local.class)
            ;

        b.bind(CircuitBreaker.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                if (!CB_ENABLED.parse(symbols.resolve(CB_ENABLED.key(), null))) {
                    return CircuitBreaker.NOOP;
                }
                return new CircuitBreakerDefault(
                    CB_FAILURE_THRESHOLD.parse(symbols.resolve(CB_FAILURE_THRESHOLD.key(), null)),
                    Duration.ofSeconds(CB_FAILURE_WINDOW.parse(symbols.resolve(CB_FAILURE_WINDOW.key(), null))),
                    Duration.ofSeconds(CB_OPEN_WINDOW.parse(symbols.resolve(CB_OPEN_WINDOW.key(), null))));
            })
            .marker(Local.class)
            ;

        b.bind(RateLimiter.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                return RATE_LIMIT_ENABLED.parse(symbols.resolve(RATE_LIMIT_ENABLED.key(), null))
                    ? new RateLimiterDefault(RATE_LIMIT_PER_SECOND.parse(symbols.resolve(RATE_LIMIT_PER_SECOND.key(), null)))
                    : RateLimiter.UNLIMITED;
            })
            .marker(Local.class)
            ;
    }
}
