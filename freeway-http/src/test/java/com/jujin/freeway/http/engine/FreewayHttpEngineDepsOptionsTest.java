package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's constructor inputs are split by role: {@link FreewayHttpEngine.Deps}
 * contains required collaborators, while {@link FreewayHttpEngine.Options}
 * contains caller-selected transport and protocol policy. Each wither sets
 * exactly one thing; the h2 reset guard is the one exception, because its count
 * and window are one decision.
 */
class FreewayHttpEngineDepsOptionsTest {

    @Test
    void depsDefaultsProvideNoopMetrics() {
        var deps = FreewayHttpEngine.Deps.defaults(
            new JsonCodecDefault(), new CoercerDefault());

        assertSame(NoopMetrics.INSTANCE, deps.metrics(),
            "the engine never has \"no metrics\", only the noop sink");
    }

    @Test
    void optionsDefaultsArePlainHttpWithTheDefaultResetGuard() {
        var options = FreewayHttpEngine.Options.defaults();

        assertNull(options.sslContext(), "no TLS unless asked for");
        assertFalse(options.http2OverSsl());
        assertNull(options.sslParameters());
        assertEquals(FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT,
            options.h2ResetBurstLimit(), "the h2 guard default moves with Options");
        assertEquals(FreewayHttpEngine.DEFAULT_H2_RESET_WINDOW, options.h2ResetWindow());
    }

    @Test
    void withersDoNotClobberEachOther() {
        // A non-noop sink makes "metrics were reset" distinguishable from
        // "metrics were preserved". Deps and Options must stay independent.
        Metrics metrics = new CountingMetrics();
        SSLContext ssl = sslContext();
        SSLParameters parameters = new SSLParameters();

        var deps = FreewayHttpEngine.Deps.defaults(
                new JsonCodecDefault(), new CoercerDefault())
            .withMetrics(metrics);
        var options = FreewayHttpEngine.Options.defaults()
            .withH2Reset(50, Duration.ofSeconds(5))
            .withSslParameters(parameters)
            .withSsl(ssl, true);

        assertSame(metrics, deps.metrics(), "withSsl must not reset metrics");
        assertSame(parameters, options.sslParameters());
        assertSame(ssl, options.sslContext());
        assertTrue(options.http2OverSsl());
        assertEquals(50, options.h2ResetBurstLimit(),
            "every wither passes the h2 guard through untouched");
        assertEquals(Duration.ofSeconds(5), options.h2ResetWindow());

        // null metrics restores the noop sink rather than failing later.
        assertSame(NoopMetrics.INSTANCE, deps.withMetrics(null).metrics());
    }

    /** The guard's defaults and validation live here, not on HttpServerConfig. */
    @Test
    void withH2ResetTunesWithoutTouchingTheOriginal() {
        var original = FreewayHttpEngine.Options.defaults();
        var tuned = original.withH2Reset(50, Duration.ofSeconds(5));

        assertEquals(50, tuned.h2ResetBurstLimit());
        assertEquals(Duration.ofSeconds(5), tuned.h2ResetWindow());
        assertEquals(FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT,
            original.h2ResetBurstLimit(), "the record stays immutable");
    }

    /** Validation moved here with the knob, so a bad guard fails at wiring. */
    @Test
    void withH2ResetRunsTheValidationTheConfigUsedToCarry() {
        var options = FreewayHttpEngine.Options.defaults();

        assertThrows(IllegalArgumentException.class,
            () -> options.withH2Reset(-1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> options.withH2Reset(10, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
            () -> options.withH2Reset(10, null));
    }

    /** A sink that is not {@link NoopMetrics}, so identity proves preservation. */
    private static final class CountingMetrics implements Metrics {

        private final AtomicInteger counters = new AtomicInteger();

        @Override
        public Counter counter(String name) {
            counters.incrementAndGet();
            return new Counter() {
                @Override
                public void increment() {
                }

                @Override
                public void add(long delta) {
                }

                @Override
                public long value() {
                    return 0;
                }
            };
        }

        @Override
        public void gauge(String name, java.util.function.Supplier<Number> value) {
        }

        @Override
        public String toString() {
            return "CountingMetrics[" + counters.get() + "]";
        }
    }

    private static SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
