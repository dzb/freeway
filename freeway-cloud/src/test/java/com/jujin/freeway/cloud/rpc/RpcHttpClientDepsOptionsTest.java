package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.ContextCodec;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.resilience.RetryerDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Wither coverage for the client's split inputs: {@link RpcHttpClientDefault.Deps}
 * contains collaborators, while {@link RpcHttpClientDefault.Options} contains
 * caller-selected timing policy. Each wither replaces exactly its own field and
 * leaves the others at their defaults, so direct callers never need a
 * ten-argument constructor. Also pins the library-fallback timeout defaults to
 * the shared {@link RpcModule.ConfigKeys} sources.
 */
class RpcHttpClientDepsOptionsTest {

    private static final ContextCodec NOOP_CODEC = new ContextCodec() {
        @Override
        public void encode(InvocationContext ctx, Map<String, String> headers) {
            // no-op for the field test
        }

        @Override
        public InvocationContext decode(Map<String, String> headers) {
            return null;
        }
    };

    @Test
    void depsWithersReplaceOnlyTheirTargetedField() {
        Retryer retryer = RetryerDefault.withDefaults();
        CircuitBreaker breaker = CircuitBreaker.NOOP;
        RateLimiter limiter = RateLimiter.UNLIMITED;

        RpcHttpClientDefault.Deps deps = RpcHttpClientDefault.Deps.defaults()
            .withContextCodecs(List.of(NOOP_CODEC))
            .withRetryer(retryer)
            .withBreaker(breaker)
            .withRateLimiter(limiter)
            .withTransport(TransportSecurity.NONE);

        assertEquals(List.of(NOOP_CODEC), deps.codecs());
        assertSame(retryer, deps.retryer());
        assertSame(breaker, deps.breaker());
        assertSame(limiter, deps.rateLimiter());
        assertSame(TransportSecurity.NONE, deps.transport());
        // Untouched collaborators keep their defaults.
        assertNull(deps.tracer());
        assertNull(deps.metrics());
    }

    @Test
    void optionsWithersReplaceOnlyTheirTargetedField() {
        Duration requestTimeout = Duration.ofMillis(7000);
        Duration connectTimeout = Duration.ofMillis(2500);
        Duration shutdownGrace = Duration.ofMillis(300);

        RpcHttpClientDefault.Options options = RpcHttpClientDefault.Options.defaults()
            .withRequestTimeout(requestTimeout)
            .withConnectTimeout(connectTimeout)
            .withShutdownGrace(shutdownGrace);

        assertEquals(requestTimeout, options.requestTimeout());
        assertEquals(connectTimeout, options.connectTimeout());
        assertEquals(shutdownGrace, options.shutdownGrace());
    }

    @Test
    void nullRequestOrConnectTimeoutFallsBackToTheLibraryDefault() {
        RpcHttpClientDefault.Options options = RpcHttpClientDefault.Options.defaults();
        assertEquals(RpcHttpClientDefault.Options.DEFAULT_REQUEST_TIMEOUT,
            options.requestTimeout());
        assertEquals(RpcHttpClientDefault.Options.DEFAULT_CONNECT_TIMEOUT,
            options.connectTimeout());
        // A wither may intentionally reset a timeout to null — the compact
        // constructor's normalization then reapplies the library default.
        assertEquals(RpcHttpClientDefault.Options.DEFAULT_REQUEST_TIMEOUT,
            options.withRequestTimeout(null).requestTimeout());
        assertEquals(RpcHttpClientDefault.Options.DEFAULT_CONNECT_TIMEOUT,
            options.withConnectTimeout(null).connectTimeout());
    }

    @Test
    void defaultTimeoutsStayPinnedToTheSharedConfigKeysValues() {
        assertEquals(Duration.ofMillis(RpcModule.ConfigKeys.RPC_REQUEST_TIMEOUT_DEFAULT),
            RpcHttpClientDefault.Options.DEFAULT_REQUEST_TIMEOUT);
        assertEquals(Duration.ofMillis(RpcModule.ConfigKeys.RPC_CONNECT_TIMEOUT_DEFAULT),
            RpcHttpClientDefault.Options.DEFAULT_CONNECT_TIMEOUT);
    }
}
