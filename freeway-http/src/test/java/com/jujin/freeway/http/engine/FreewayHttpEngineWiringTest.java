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
 * The engine's optional wiring is a value with named knobs, not a constructor
 * ladder: each wither sets exactly one thing. The h2 reset guard is the one
 * exception that proves the rule's intent — its count and window are a single
 * decision (a zero count makes the window meaningless), so one wither takes
 * both.
 */
class FreewayHttpEngineWiringTest {

    @Test
    void defaultsArePlainHttpWithNoopMetrics() {
        var wiring = FreewayHttpEngine.Wiring.defaults(
            new JsonCodecDefault(), new CoercerDefault());

        assertNull(wiring.sslContext(), "no TLS unless asked for");
        assertFalse(wiring.http2OverSsl());
        assertNull(wiring.sslParameters());
        assertSame(NoopMetrics.INSTANCE, wiring.metrics(),
            "the engine never has \"no metrics\", only the noop sink");
        assertEquals(FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT,
            wiring.h2ResetBurstLimit(), "the h2 guard default moves with Wiring");
        assertEquals(FreewayHttpEngine.DEFAULT_H2_RESET_WINDOW, wiring.h2ResetWindow());
    }

    @Test
    void withersDoNotClobberEachOther() {
        // The constructor ladder this replaced had a step that added metrics and
        // a later step that reset them to the noop sink, so
        // `(codec, coercer, ssl, http2)` silently dropped an earlier
        // `(codec, coercer, metrics)` call. Each wither now touches one field.
        // A non-noop sink: passing NoopMetrics would make "metrics were reset to
        // the noop sink" indistinguishable from "metrics were preserved" — the
        // assertion could not fail, which is how this check was useless once.
        Metrics metrics = new CountingMetrics();
        SSLContext ssl = sslContext();
        SSLParameters parameters = new SSLParameters();

        var wiring = FreewayHttpEngine.Wiring.defaults(
                new JsonCodecDefault(), new CoercerDefault())
            .withH2Reset(50, Duration.ofSeconds(5))
            .withMetrics(metrics)
            .withSslParameters(parameters)
            .withSsl(ssl, true);

        assertSame(metrics, wiring.metrics(), "withSsl must not reset metrics");
        assertSame(parameters, wiring.sslParameters());
        assertSame(ssl, wiring.sslContext());
        assertTrue(wiring.http2OverSsl());
        assertEquals(50, wiring.h2ResetBurstLimit(),
            "every wither passes the h2 guard through untouched");
        assertEquals(Duration.ofSeconds(5), wiring.h2ResetWindow());

        // null metrics restores the noop sink rather than failing later.
        assertSame(NoopMetrics.INSTANCE, wiring.withMetrics(null).metrics());
    }

    /** The guard's defaults and validation live here, not on HttpServerConfig. */
    @Test
    void withH2ResetTunesWithoutTouchingTheOriginal() {
        var original = FreewayHttpEngine.Wiring.defaults(
            new JsonCodecDefault(), new CoercerDefault());
        var tuned = original.withH2Reset(50, Duration.ofSeconds(5));

        assertEquals(50, tuned.h2ResetBurstLimit());
        assertEquals(Duration.ofSeconds(5), tuned.h2ResetWindow());
        assertEquals(FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT,
            original.h2ResetBurstLimit(), "the record stays immutable");
    }

    /** Validation moved here with the knob, so a bad guard fails at wiring. */
    @Test
    void withH2ResetRunsTheValidationTheConfigUsedToCarry() {
        var w = FreewayHttpEngine.Wiring.defaults(
            new JsonCodecDefault(), new CoercerDefault());

        assertThrows(IllegalArgumentException.class,
            () -> w.withH2Reset(-1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> w.withH2Reset(10, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
            () -> w.withH2Reset(10, null));
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
