package com.jujin.freeway.commons.config;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigSpec} contracts: coercer-parsed and parser-parsed forms,
 * required-key fail-fast, error messages that name the key, and the
 * no-parser/no-coercer guard.
 */
class ConfigSpecTest {

    private final Coercer coercer = new CoercerDefault();

    @Test
    void coercerParsedOptionalKeyReturnsDefaultWhenAbsent() {
        ConfigSpec<Integer> port = ConfigSpec.of("server.port", Integer.class, 8080);
        assertEquals(8080, port.parse(null, coercer));
        assertEquals(8080, port.parse("   ", coercer));
        assertTrue(port.description().isEmpty());
        assertFalse(port.required());
    }

    @Test
    void coercerParsesNonScalarTargets() {
        ConfigSpec<Duration> ttl = ConfigSpec.of(
            "freeway.db.lock-ttl", Duration.class, Duration.ofHours(1));
        assertEquals(Duration.ofSeconds(30), ttl.parse("PT30S", coercer));
    }

    @Test
    void explicitParserFormParsesStrippedRawValue() {
        ConfigSpec<Integer> port = ConfigSpec.of(
            "server.port", Integer.class, 8080, Integer::parseInt);
        assertEquals(9090, port.parse(" 9090 ", coercer));
    }

    @Test
    void explicitParserWinsOverCoercer() {
        Coercer hostile = new Coercer() {
            @Override
            public <T> T coerce(Object value, Class<T> targetType) {
                throw new AssertionError("coercer must not be consulted");
            }
        };
        ConfigSpec<Integer> port = ConfigSpec.of(
            "server.port", Integer.class, 8080, Integer::parseInt);
        assertEquals(1, port.parse("1", hostile));
    }

    @Test
    void invalidValueReportsKeyAndRawInput() {
        ConfigSpec<Integer> port = ConfigSpec.of(
            "server.port", Integer.class, 8080);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> port.parse("not-a-number", coercer));
        assertTrue(ex.getMessage().contains("server.port"),
            "error must name the key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not-a-number"),
            "error must carry the raw value: " + ex.getMessage());
    }

    @Test
    void requiredKeyFailsFastWhenAbsentOrBlank() {
        ConfigSpec<String> url = ConfigSpec.required(
            "freeway.db.url", String.class, s -> s);
        IllegalArgumentException absent = assertThrows(
            IllegalArgumentException.class, () -> url.parse(null, coercer));
        assertTrue(absent.getMessage().contains("freeway.db.url"));
        IllegalArgumentException blank = assertThrows(
            IllegalArgumentException.class, () -> url.parse("  ", coercer));
        assertTrue(blank.getMessage().contains("freeway.db.url"));
    }

    @Test
    void requiredCoercerParsedKeyResolvesWhenPresent() {
        ConfigSpec<Duration> ttl = ConfigSpec.required(
            "freeway.db.migration.lock-ttl", Duration.class);
        assertTrue(ttl.required());
        assertEquals(Duration.ofMinutes(5), ttl.parse("PT5M", coercer));
    }

    @Test
    void noParserSpecRejectsParserlessParseForm() {
        ConfigSpec<Integer> port = ConfigSpec.of("server.port", Integer.class, 8080);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> port.parse("8080"));
        assertTrue(ex.getMessage().contains("parse(raw, Coercer)"));
    }

    @Test
    void noParserAndNoCoercerFailsWithKeyNameInsteadOfNpe() {
        ConfigSpec<Duration> ttl = ConfigSpec.of(
            "freeway.db.lock-ttl", Duration.class, Duration.ofHours(1));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ttl.parse("PT1H", null));
        assertTrue(ex.getMessage().contains("freeway.db.lock-ttl"),
            "error must name the key: " + ex.getMessage());
        // Absent values never reach the parser path — the default returns.
        assertEquals(Duration.ofHours(1), ttl.parse(null, null));
    }

    @Test
    void blankKeyIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
            () -> ConfigSpec.of(" ", Integer.class, 0));
        assertThrows(IllegalArgumentException.class,
            () -> ConfigSpec.required("", String.class, s -> s));
    }

    @Test
    void parserIsInvokedAtMostOncePerParseCall() {
        AtomicInteger invocations = new AtomicInteger();
        ConfigSpec<Integer> spec = ConfigSpec.of(
            "counter.key", Integer.class, 0,
            raw -> {
                invocations.incrementAndGet();
                return Integer.parseInt(raw);
            });
        assertEquals(7, spec.parse("7", coercer));
        assertEquals(1, invocations.get());
    }
}
