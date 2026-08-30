package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.CircuitBreakerDefault;
import com.jujin.freeway.cloud.internal.ConfigValues;
import com.jujin.freeway.cloud.internal.RateLimiterDefault;
import com.jujin.freeway.cloud.internal.RetryerDefault;
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

    @Override
    public void bind(Binder b) {
        b.bind(Retryer.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                return new RetryerDefault(
                    ConfigValues.intValue(symbols, CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS,
                        CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS_DEFAULT),
                    ConfigValues.longValue(symbols, CloudConfigKeys.RPC_RETRY_BACKOFF_BASE,
                        CloudConfigKeys.RPC_RETRY_BACKOFF_BASE_DEFAULT),
                    ConfigValues.longValue(symbols, CloudConfigKeys.RPC_RETRY_BACKOFF_MAX,
                        CloudConfigKeys.RPC_RETRY_BACKOFF_MAX_DEFAULT));
            })
            .marker(Local.class)
            ;

        b.bind(CircuitBreaker.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                boolean enabled = Boolean.parseBoolean(symbols.resolve(CloudConfigKeys.RPC_CB_ENABLED, "true"));
                if (!enabled) {
                    return CircuitBreaker.NOOP;
                }
                int threshold = ConfigValues.intValue(symbols, CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD,
                    CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD_DEFAULT);
                long windowSeconds = ConfigValues.longValue(symbols, CloudConfigKeys.RPC_CB_FAILURE_WINDOW,
                    CloudConfigKeys.RPC_CB_FAILURE_WINDOW_DEFAULT);
                long openSeconds = ConfigValues.longValue(symbols, CloudConfigKeys.RPC_CB_OPEN_WINDOW,
                    CloudConfigKeys.RPC_CB_OPEN_WINDOW_DEFAULT);
                return new CircuitBreakerDefault(threshold,
                    Duration.ofSeconds(windowSeconds), Duration.ofSeconds(openSeconds));
            })
            .marker(Local.class)
            ;

        b.bind(RateLimiter.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                boolean enabled = Boolean.parseBoolean(symbols.resolve(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, "false"));
                double perSecond = ConfigValues.doubleValue(symbols, CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND,
                    CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND_DEFAULT);
                return enabled ? new RateLimiterDefault(perSecond) : new RateLimiterDefault(Double.MAX_VALUE);
            })
            .marker(Local.class)
            ;
    }
}
