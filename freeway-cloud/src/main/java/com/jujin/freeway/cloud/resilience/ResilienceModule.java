package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.time.Duration;
import java.util.function.Function;


/**
 * IoC wiring for resilience: {@link Retryer} / {@link CircuitBreaker} /
 * {@link RateLimiter} defaults, configured from {@code freeway.cloud.rpc.*}
 * keys ({@code @Local} marker). The aggregate switch
 * {@code freeway.cloud.rpc.resilience = auto | off} gates the whole bundle:
 * {@code auto} (default) lets the fine-grained keys govern; {@code off} binds
 * the inert set ({@code NO_RETRY} / {@code NOOP} / {@code UNLIMITED}) and
 * ignores every fine-grained key — a kill switch for mesh takeover and
 * diagnosis, never a silent default. The {@code RpcHttpClient}
 * layer applies the bound pieces uniformly; Advisor-based weaving for local
 * services is a later, optional addition.
 */
@Marker(Builtin.class)
public final class ResilienceModule implements ModuleEx {

    // Key, type and default declared once per key; the symbol chain resolves
    // the raw value and the spec post-processes it. Defaults come from the
    // shared ConfigKeys sources so the config layer and the library
    // fallback (RpcHttpClientDefault) cannot drift apart.
    /**
     * Aggregate mode: auto = fine-grained keys govern; off = kill switch that
     * disables retry, breaker and limiter as one explicit switch and ignores
     * the fine-grained rpc.* resilience keys.
     */
    private static final SymbolSpec<String> RESILIENCE_MODE = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RESILIENCE, String.class,
        ResilienceModule.ConfigKeys.RPC_RESILIENCE_AUTO, Function.identity());
    private static final SymbolSpec<Integer> RETRY_MAX_ATTEMPTS = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, Integer.class,
        ResilienceModule.ConfigKeys.RPC_RETRY_MAX_ATTEMPTS_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Long> RETRY_BACKOFF_BASE = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RETRY_BACKOFF_BASE, Long.class,
        ResilienceModule.ConfigKeys.RPC_RETRY_BACKOFF_BASE_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> RETRY_BACKOFF_MAX = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RETRY_BACKOFF_MAX, Long.class,
        ResilienceModule.ConfigKeys.RPC_RETRY_BACKOFF_MAX_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Integer> RETRY_BUDGET = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RETRY_BUDGET, Integer.class,
        ResilienceModule.ConfigKeys.RPC_RETRY_BUDGET_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Boolean> CB_ENABLED = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_CB_ENABLED, Boolean.class, true);
    private static final SymbolSpec<Integer> CB_FAILURE_THRESHOLD = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_CB_FAILURE_THRESHOLD, Integer.class,
        ResilienceModule.ConfigKeys.RPC_CB_FAILURE_THRESHOLD_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Long> CB_FAILURE_WINDOW = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_CB_FAILURE_WINDOW, Long.class,
        ResilienceModule.ConfigKeys.RPC_CB_FAILURE_WINDOW_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> CB_OPEN_WINDOW = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_CB_OPEN_WINDOW, Long.class,
        ResilienceModule.ConfigKeys.RPC_CB_OPEN_WINDOW_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Boolean> RATE_LIMIT_ENABLED = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RATE_LIMIT_ENABLED, Boolean.class, false);
    private static final SymbolSpec<Double> RATE_LIMIT_PER_SECOND = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_RATE_LIMIT_PER_SECOND, Double.class,
        ResilienceModule.ConfigKeys.RPC_RATE_LIMIT_PER_SECOND_DEFAULT, Double::parseDouble);

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX));
        // Fail startup on an invalid aggregate mode — the binder lambdas run
        // lazily behind the container's proxies, so without this hook a typo
        // would surface at the first RPC call instead of at startup.
        b.contribute(RuntimeHook.class)
            .add(CloudHookIds.RESILIENCE,
                (Container container) ->
                    mode(container.get(SymbolSource.class)))
            ;

        b.bind(Retryer.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                if (mode(symbols) == Mode.OFF) {
                    return Retryer.NO_RETRY;
                }
                return new RetryerDefault(
                    symbols.resolve(RETRY_MAX_ATTEMPTS),
                    symbols.resolve(RETRY_BACKOFF_BASE),
                    symbols.resolve(RETRY_BACKOFF_MAX));
            })
            .marker(Local.class)
            ;

        b.bind(RetryBudget.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                if (mode(symbols) == Mode.OFF) {
                    return RetryBudget.NO_LIMIT;
                }
                int capacity = symbols.resolve(RETRY_BUDGET);
                return capacity <= 0
                    ? RetryBudget.NO_LIMIT
                    : new RetryBudgetDefault(capacity);
            })
            .marker(Local.class)
            ;

        b.bind(CircuitBreaker.class)
            .to((Container container) -> {
                SymbolSource symbols = container.get(SymbolSource.class);
                if (mode(symbols) == Mode.OFF) {
                    return CircuitBreaker.NOOP;
                }
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
                if (mode(symbols) == Mode.OFF) {
                    return RateLimiter.UNLIMITED;
                }
                return symbols.resolve(RATE_LIMIT_ENABLED)
                    ? new RateLimiterDefault(symbols.resolve(RATE_LIMIT_PER_SECOND))
                    : RateLimiter.UNLIMITED;
            })
            .marker(Local.class)
            ;
    }

    /** The aggregate mode, validated eagerly: an unknown value fails startup
     *  with the key name instead of degrading silently. */
    private static Mode mode(SymbolSource symbols) {
        return SymbolSpec.mode(ResilienceModule.ConfigKeys.RPC_RESILIENCE,
            symbols.resolve(RESILIENCE_MODE), BY_TOKEN, Mode.AUTO);
    }

    private static final java.util.Map<String, Mode> BY_TOKEN = java.util.Map.of(
        ResilienceModule.ConfigKeys.RPC_RESILIENCE_AUTO, Mode.AUTO,
        ResilienceModule.ConfigKeys.RPC_RESILIENCE_OFF, Mode.OFF);

    private enum Mode { AUTO, OFF }
    /** Keys read by the resilience bundle. */
    public static final class ConfigKeys {
        private ConfigKeys() {}
        public static final String RPC_RESILIENCE = "freeway.cloud.rpc.resilience";
        public static final String RPC_RESILIENCE_AUTO = "auto";
        public static final String RPC_RESILIENCE_OFF = "off";
        public static final String RPC_RETRY_MAX_ATTEMPTS = "freeway.cloud.rpc.retry.max-attempts";
        public static final int RPC_RETRY_MAX_ATTEMPTS_DEFAULT = 3;
        public static final String RPC_RETRY_BACKOFF_BASE = "freeway.cloud.rpc.retry.backoff-base";
        public static final long RPC_RETRY_BACKOFF_BASE_DEFAULT = 100;
        public static final String RPC_RETRY_BACKOFF_MAX = "freeway.cloud.rpc.retry.backoff-max";
        public static final long RPC_RETRY_BACKOFF_MAX_DEFAULT = 5000;
        public static final String RPC_RETRY_BUDGET = "freeway.cloud.rpc.retry.budget";
        public static final int RPC_RETRY_BUDGET_DEFAULT = 20;
        public static final String RPC_CB_ENABLED = "freeway.cloud.rpc.circuit-breaker.enabled";
        public static final String RPC_CB_FAILURE_THRESHOLD = "freeway.cloud.rpc.circuit-breaker.failure-threshold";
        public static final int RPC_CB_FAILURE_THRESHOLD_DEFAULT = 5;
        public static final String RPC_CB_FAILURE_WINDOW = "freeway.cloud.rpc.circuit-breaker.failure-window";
        public static final long RPC_CB_FAILURE_WINDOW_DEFAULT = 60;
        public static final String RPC_CB_OPEN_WINDOW = "freeway.cloud.rpc.circuit-breaker.open-window";
        public static final long RPC_CB_OPEN_WINDOW_DEFAULT = 30;
        public static final String RPC_RATE_LIMIT_ENABLED = "freeway.cloud.rpc.rate-limit.enabled";
        public static final String RPC_RATE_LIMIT_PER_SECOND = "freeway.cloud.rpc.rate-limit.per-second";
        public static final double RPC_RATE_LIMIT_PER_SECOND_DEFAULT = 100;
    }
}
