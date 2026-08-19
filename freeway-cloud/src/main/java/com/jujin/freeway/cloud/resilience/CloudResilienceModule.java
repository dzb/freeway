package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.CircuitBreakerDefault;
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
 * keys ({@code @Local} + {@code .primary()}). The {@code CloudHttpClient}
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
                int max = Integer.parseInt(symbols.resolve(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "3"));
                long base = Long.parseLong(symbols.resolve(CloudConfigKeys.RPC_RETRY_BACKOFF_BASE, "100"));
                long maxMs = Long.parseLong(symbols.resolve(CloudConfigKeys.RPC_RETRY_BACKOFF_MAX, "5000"));
                return new RetryerDefault(max, base, maxMs);
            })
            .marker(Local.class)
            .primary();

        b.bind(CircuitBreaker.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                boolean enabled = Boolean.parseBoolean(symbols.resolve(CloudConfigKeys.RPC_CB_ENABLED, "true"));
                if (!enabled) {
                    return CircuitBreaker.NOOP;
                }
                int threshold = Integer.parseInt(symbols.resolve(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD, "5"));
                long openSeconds = Long.parseLong(symbols.resolve(CloudConfigKeys.RPC_CB_OPEN_WINDOW, "30"));
                return new CircuitBreakerDefault(threshold, Duration.ofSeconds(60), Duration.ofSeconds(openSeconds));
            })
            .marker(Local.class)
            .primary();

        b.bind(RateLimiter.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                boolean enabled = Boolean.parseBoolean(symbols.resolve(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, "false"));
                double perSecond = Double.parseDouble(symbols.resolve(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND, "100"));
                return enabled ? new RateLimiterDefault(perSecond) : new RateLimiterDefault(Double.MAX_VALUE);
            })
            .marker(Local.class)
            .primary();
    }
}
