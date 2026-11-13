package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.resilience.RetryerDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.cloud.CloudModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Wither coverage for {@link RpcHttpClientDefault.Wiring} (P2-7): each
 * wither replaces exactly its own field and leaves the others at their
 * defaults, so direct callers never need the nine-argument constructor. Also
 * pins the library-fallback timeout defaults to the shared
 * {@link ConfigKeys} sources (P1-5) — if the two layers drift apart,
 * this fails.
 */
class RpcHttpClientWiringTest {

    private static final Propagator NOOP_PROPAGATOR = new Propagator() {
        @Override
        public void inject(InvocationContext ctx, Map<String, String> headers) {
            // no-op for the field test
        }

        @Override
        public InvocationContext extract(Map<String, String> headers) {
            return null;
        }
    };

    @Test
    void withersReplaceOnlyTheirTargetedField() {
        Retryer retryer = RetryerDefault.withDefaults();
        CircuitBreaker breaker = CircuitBreaker.NOOP;
        RateLimiter limiter = RateLimiter.UNLIMITED;
        Duration requestTimeout = Duration.ofMillis(7000);
        Duration connectTimeout = Duration.ofMillis(2500);

        RpcHttpClientDefault.Wiring wiring = RpcHttpClientDefault.Wiring.defaults()
            .withPropagators(List.of(NOOP_PROPAGATOR))
            .withRetryer(retryer)
            .withBreaker(breaker)
            .withRateLimiter(limiter)
            .withTransport(TransportSecurity.NONE)
            .withRequestTimeout(requestTimeout)
            .withConnectTimeout(connectTimeout);

        assertEquals(List.of(NOOP_PROPAGATOR), wiring.propagators());
        assertSame(retryer, wiring.retryer());
        assertSame(breaker, wiring.breaker());
        assertSame(limiter, wiring.rateLimiter());
        assertSame(TransportSecurity.NONE, wiring.transport());
        assertEquals(requestTimeout, wiring.requestTimeout());
        assertEquals(connectTimeout, wiring.connectTimeout());
        // Untouched components keep their defaults.
        assertNull(wiring.tracer());
        assertNull(wiring.metrics());
    }

    @Test
    void nullRequestOrConnectTimeoutFallsBackToTheLibraryDefault() {
        RpcHttpClientDefault.Wiring wiring = RpcHttpClientDefault.Wiring.defaults();
        assertEquals(RpcHttpClientDefault.Wiring.DEFAULT_REQUEST_TIMEOUT, wiring.requestTimeout());
        assertEquals(RpcHttpClientDefault.Wiring.DEFAULT_CONNECT_TIMEOUT, wiring.connectTimeout());
        // A wither may intentionally reset a timeout to null — the compact
        // constructor's normalization then reapplies the library default.
        assertEquals(RpcHttpClientDefault.Wiring.DEFAULT_REQUEST_TIMEOUT,
            wiring.withRequestTimeout(null).requestTimeout());
        assertEquals(RpcHttpClientDefault.Wiring.DEFAULT_CONNECT_TIMEOUT,
            wiring.withConnectTimeout(null).connectTimeout());
    }

    @Test
    void defaultTimeoutsStayPinnedToTheSharedConfigKeysValues() {
        assertEquals(Duration.ofMillis(ConfigKeys.RPC_REQUEST_TIMEOUT_DEFAULT),
            RpcHttpClientDefault.Wiring.DEFAULT_REQUEST_TIMEOUT);
        assertEquals(Duration.ofMillis(ConfigKeys.RPC_CONNECT_TIMEOUT_DEFAULT),
            RpcHttpClientDefault.Wiring.DEFAULT_CONNECT_TIMEOUT);
    }
}
