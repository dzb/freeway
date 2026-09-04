package com.jujin.freeway.commons.coercion;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoercerImplTest {
    private final CoercerImpl coercer = new CoercerImpl();

    private enum Color {
        RED
    }

    private enum Status {
        SUCCESS,
        FAILURE
    }

    // --- supports tests ---

    @Test
    void supportsBuiltinScalars() {
        assertTrue(coercer.supports(String.class, Integer.class));
        assertTrue(coercer.supports(String.class, int.class));
        assertTrue(coercer.supports(String.class, Long.class));
        assertTrue(coercer.supports(String.class, Double.class));
        assertTrue(coercer.supports(String.class, Boolean.class));
        assertTrue(coercer.supports(String.class, boolean.class));
        assertTrue(coercer.supports(String.class, Character.class));
        assertTrue(coercer.supports(String.class, char.class));
        assertTrue(coercer.supports(String.class, BigDecimal.class));
        assertTrue(coercer.supports(String.class, BigInteger.class));
        assertTrue(coercer.supports(String.class, LocalDate.class));
        assertTrue(coercer.supports(String.class, Instant.class));
        assertTrue(coercer.supports(String.class, UUID.class));
        assertTrue(coercer.supports(Integer.class, String.class));
        assertTrue(coercer.supports(Integer.class, Long.class));
        assertTrue(coercer.supports(Integer.class, double.class));
        assertTrue(coercer.supports(Double.class, Integer.class));
        assertTrue(coercer.supports(BigDecimal.class, BigInteger.class));
    }

    @Test
    void supportsNullToAny() {
        assertTrue(coercer.supports(Void.class, String.class));
        assertTrue(coercer.supports(Void.class, int.class));
        assertTrue(coercer.supports(Void.class, Boolean.class));
    }

    @Test
    void supportsIdentityOrSubtype() {
        assertTrue(coercer.supports(String.class, String.class));
        assertTrue(coercer.supports(Integer.class, Object.class));
        assertTrue(coercer.supports(Integer.class, Number.class));
        assertTrue(coercer.supports(Integer.class, Comparable.class));
    }

    @Test
    void supportsEnum() {
        assertTrue(coercer.supports(String.class, Color.class));
        assertTrue(coercer.supports(String.class, Status.class));
    }

    @Test
    void cannotCoerceUnsupported() {
        assertFalse(coercer.supports(Integer.class, Pattern.class));
        assertFalse(coercer.supports(Color.class, Pattern.class));
    }

    @Test
    void coerceNumberRejectsNaN() {
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(Double.NaN, Integer.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(Double.POSITIVE_INFINITY, Long.class));
    }

    @Test
    void coerceNumberRejectsOversized() {
        BigInteger huge = new BigInteger("99999999999999999999");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(huge, Integer.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(huge, Short.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(huge, Long.class),
            "BigInteger beyond Long range must throw, not wrap");
    }

    @Test
    void coerceNonExactNumberOverflowThrows() {
        // Long/Double/Float sources must fail loudly, not silently wrap.
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(3_000_000_000L, Integer.class),
            "Long beyond Integer range must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(3_000_000_000L, Short.class),
            "Long beyond Short range must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(9_000_000_000L, Byte.class),
            "Long beyond Byte range must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(1e30, Integer.class),
            "double beyond Integer range must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(1e30, Long.class),
            "double beyond Long range must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(Float.MAX_VALUE, Short.class),
            "float beyond Short range must throw, not wrap");
        // In-range fractional sources still truncate like the string path.
        assertEquals(Integer.valueOf(3), coercer.coerce(3.9, Integer.class));
        assertEquals(Integer.valueOf(0), coercer.coerce(0.5, Integer.class));
        assertEquals(Long.valueOf(-2L), coercer.coerce(-2.9, Long.class));
    }

    @Test
    void coerceStringOverflowThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("2147483648", Integer.class),
            "out-of-range integer literal must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("9223372036854775808", Long.class),
            "out-of-range long literal must throw, not wrap");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("1e30", Integer.class),
            "huge exponent must throw, not wrap");
    }

    @Test
    void coerceInRangeDecimalTruncates() {
        assertEquals(Integer.valueOf(1), coercer.coerce("1.5", Integer.class));
        assertEquals(Long.valueOf(-2L), coercer.coerce("-2.9", Long.class));
    }

    @Test
    void shortAndByteMatchIntegerDecimalSemantics() {
        // Fractional strings truncate consistently across all integral types
        // (previously Short/Byte threw on "1.5" while Integer returned 1).
        assertEquals(Short.valueOf((short) 1), coercer.coerce("1.5", Short.class));
        assertEquals(Byte.valueOf((byte) 1), coercer.coerce("1.5", Byte.class));
        assertEquals(Short.valueOf((short) -2), coercer.coerce("-2.9", Short.class));
        // Out-of-range fractional values fail loudly, not wrap.
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("32768.9", Short.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("128.5", Byte.class));
    }

    @Test
    void supportsWithCustomRule() {
        CoercerImpl c = new CoercerImpl()
            .register(new CoerceRule<>(String.class, Duration.class, Duration::parse));

        assertTrue(c.supports(String.class, Duration.class));
        // Duration is a built-in scalar target — any source can coerce to it
        assertTrue(c.supports(Integer.class, Duration.class));
    }

    @Test
    void supportsWithCompatibleSourceType() {
        // CharSequence -> Duration 规则，String（子类）也应通过
        CoercerImpl c = new CoercerImpl()
            .register(new CoerceRule<>(CharSequence.class, Duration.class,
                s -> Duration.parse(s.toString())));

        assertTrue(c.supports(String.class, Duration.class));
        assertTrue(c.supports(StringBuilder.class, Duration.class));
        assertTrue(c.supports(CharSequence.class, Duration.class));
    }

    @Test
    void supportsRejectsNullParams() {
        assertThrows(NullPointerException.class, () -> coercer.supports(null, String.class));
        assertThrows(NullPointerException.class, () -> coercer.supports(String.class, null));
    }

    // --- conversions tests ---

    @Test
    void supportedIncludesCustomRules() {
        CoercerImpl c = new CoercerImpl()
            .register(new CoerceRule<>(String.class, Duration.class, Duration::parse));

        Map<Class<?>, Set<Class<?>>> result = c.conversions();
        assertTrue(result.containsKey(Duration.class));
        assertTrue(result.get(Duration.class).contains(String.class));
    }

    @Test
    void supportedIncludesBuiltins() {
        Map<Class<?>, Set<Class<?>>> result = coercer.conversions();

        // 所有内置标量目标类型都应出现
        assertTrue(result.containsKey(String.class));
        assertTrue(result.containsKey(Integer.class));
        assertTrue(result.containsKey(int.class));
        assertTrue(result.containsKey(Boolean.class));
        assertTrue(result.containsKey(BigDecimal.class));

        // 内置转换的源类型标记为 Object.class
        assertTrue(result.get(String.class).contains(Object.class));
        assertTrue(result.get(Integer.class).contains(Object.class));
    }

    @Test
    void supportedIncludesBuiltinDuration() {
        // Duration is now a built-in scalar target
        Map<Class<?>, Set<Class<?>>> result = coercer.conversions();
        assertTrue(result.containsKey(Duration.class));
        assertTrue(result.get(Duration.class).contains(Object.class));
    }

    @Test
    void supportedIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () ->
            coercer.conversions().put(Integer.class, Set.of()));
    }

    // --- existing coercion tests ---

    @Test
    void coercesPrimitivesAndCommonScalarTypes() {
        assertEquals(12, coercer.coerce("12", int.class));
        assertEquals(12L, coercer.coerce("12", long.class));
        assertEquals(true, coercer.coerce("true", boolean.class));
        assertEquals('a', coercer.coerce("abc", char.class));
        assertEquals(Color.RED, coercer.coerce("RED", Color.class));
        assertEquals(Color.RED, coercer.coerce("red", Color.class));
        assertEquals(Color.RED, coercer.coerce("Red", Color.class));
        assertEquals(new BigDecimal("12"), coercer.coerce(12, BigDecimal.class));
        assertEquals(new BigInteger("12"), coercer.coerce(12, BigInteger.class));
        assertEquals("12", coercer.coerce(12, String.class));
    }

    @Test
    void coercesTemporalAndUuidScalars() {
        assertEquals(LocalDate.of(2026, 6, 18), coercer.coerce("2026-06-18", LocalDate.class));
        assertEquals(Instant.parse("2026-06-18T01:02:03Z"), coercer.coerce("2026-06-18T01:02:03Z", Instant.class));
        assertEquals(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            coercer.coerce("550e8400-e29b-41d4-a716-446655440000", UUID.class)
        );
    }

    @Test
    void coercesAllTemporalTypes() {
        assertEquals(LocalTime.of(14, 30), coercer.coerce("14:30", LocalTime.class));
        assertEquals(LocalDateTime.of(2026, 6, 18, 14, 30),
            coercer.coerce("2026-06-18T14:30", LocalDateTime.class));
        assertEquals(OffsetTime.parse("14:30+08:00"),
            coercer.coerce("14:30+08:00", OffsetTime.class));
        assertEquals(OffsetDateTime.parse("2026-06-18T14:30+08:00"),
            coercer.coerce("2026-06-18T14:30+08:00", OffsetDateTime.class));
        assertEquals(ZonedDateTime.parse("2026-06-18T14:30+08:00[Asia/Shanghai]"),
            coercer.coerce("2026-06-18T14:30+08:00[Asia/Shanghai]", ZonedDateTime.class));
    }

    @Test
    void coercesDurationWithAllSuffixes() {
        assertEquals(Duration.ofMillis(500), coercer.coerce("500ms", Duration.class));
        assertEquals(Duration.ofSeconds(10), coercer.coerce("10s", Duration.class));
        assertEquals(Duration.ofMinutes(5), coercer.coerce("5m", Duration.class));
        assertEquals(Duration.ofHours(2), coercer.coerce("2h", Duration.class));
        assertEquals(Duration.ofMillis(1000), coercer.coerce("1000", Duration.class));
    }

    @Test
    void coercesDurationIso8601RoundTrip() {
        // Duration.toString() produces ISO-8601 ("PT1H30M"); multi-unit values
        // must round-trip instead of being misread by the single-suffix path.
        assertEquals(Duration.ofHours(1).plusMinutes(30),
            coercer.coerce("PT1H30M", Duration.class));
        assertEquals(Duration.ofMinutes(5), coercer.coerce("PT5M", Duration.class));
        assertEquals(Duration.ofSeconds(90), coercer.coerce("PT1M30S", Duration.class));
        assertEquals(Duration.ofDays(2), coercer.coerce("P2D", Duration.class));
        // Legacy single-suffix forms still work.
        assertEquals(Duration.ofMinutes(5), coercer.coerce("5m", Duration.class));
        assertEquals(Duration.ofMillis(1000), coercer.coerce("1000", Duration.class));
    }

    @Test
    void coercesBigDecimalAndBigIntegerFromString() {
        assertEquals(new BigDecimal("12.34"), coercer.coerce("12.34", BigDecimal.class));
        assertEquals(new BigInteger("42"), coercer.coerce("42", BigInteger.class));
    }

    @Test
    void coercesNumberToNumberAllPairs() {
        // int → all
        assertEquals(Long.valueOf(12), coercer.coerce(12, Long.class));
        assertEquals(Double.valueOf(12.0), coercer.coerce(12, Double.class));
        assertEquals(Float.valueOf(12f), coercer.coerce(12, Float.class));
        assertEquals(Short.valueOf((short) 12), coercer.coerce(12, Short.class));
        assertEquals(Byte.valueOf((byte) 12), coercer.coerce(12, Byte.class));
        // long → all
        assertEquals(Integer.valueOf(12), coercer.coerce(12L, Integer.class));
        assertEquals(Double.valueOf(12.0), coercer.coerce(12L, Double.class));
        // double → all
        assertEquals(Integer.valueOf(12), coercer.coerce(12.0, Integer.class));
        assertEquals(Long.valueOf(12L), coercer.coerce(12.0, Long.class));
    }

    @Test
    void coercesEnumFromNumberByOrdinal() {
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce(0, Color.class));
    }

    @Test
    void coercesInstanceDirectly() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        assertSame(date, coercer.coerce(date, LocalDate.class));
        assertSame(date, coercer.coerce(date, Object.class));
        assertSame(date, coercer.coerce(date, Comparable.class));
    }

    @Test
    void coercesInvalidTemporalThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce("not-a-date", LocalDate.class));
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce("garbage", Instant.class));
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce("not-a-uuid", UUID.class));
    }

    @Test
    void defaultsNullPrimitiveAndKeepsNullReferenceTypes() {
        assertEquals(0, coercer.coerce(null, int.class));
        assertEquals(false, coercer.coerce(null, boolean.class));
        assertNull(coercer.coerce(null, String.class));
    }

    @Test
    void coercesEnumCaseInsensitive() {
        assertEquals(Status.SUCCESS, coercer.coerce("SUCCESS", Status.class));
        assertEquals(Status.SUCCESS, coercer.coerce("success", Status.class));
        assertEquals(Status.SUCCESS, coercer.coerce("Success", Status.class));
        assertEquals(Status.FAILURE, coercer.coerce("failure", Status.class));
        assertEquals(Status.FAILURE, coercer.coerce("FAILURE", Status.class));
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce("unknown", Status.class));
    }

    @Test
    void coercesAllPrimitiveTypes() {
        assertEquals((byte) 12, coercer.coerce("12", byte.class));
        assertEquals((short) 12, coercer.coerce("12", short.class));
        assertEquals(12.5f, coercer.coerce("12.5", float.class), 0f);
        assertEquals(12.5, coercer.coerce("12.5", double.class), 0d);
        assertEquals('\0', coercer.coerce("", char.class));
    }

    @Test
    void customRuleWithSupertypeSourceIsAppliedInCoerce() {
        coercer.register(new CoerceRule<>(
            Number.class, String.class, n -> "num:" + n));

        assertTrue(coercer.supports(Integer.class, String.class));
        assertEquals("num:42", coercer.coerce(42, String.class),
            "coerce must apply rules whose source type is a supertype of the input");
    }

    @Test
    void mostSpecificCustomRuleWins() {
        coercer.register(new CoerceRule<>(
            Number.class, String.class, n -> "num"));
        coercer.register(new CoerceRule<>(
            Integer.class, String.class, n -> "int"));

        assertEquals("int", coercer.coerce(42, String.class),
            "the most specific matching rule must win");
        assertEquals("num", coercer.coerce(4.5, String.class));
    }

    @Test
    void mostSpecificAssignableRuleWinsWithoutExactMatch() {
        coercer.register(new CoerceRule<>(
            Serializable.class, String.class, n -> "serializable"));
        coercer.register(new CoerceRule<>(
            Number.class, String.class, n -> "num"));

        assertEquals("num", coercer.coerce(42, String.class),
            "the closest assignable source type must win");
    }

    @Test
    void nullInputNeverTriggersCustomRules() {
        coercer.register(new CoerceRule<>(
            Object.class, String.class, n -> "obj"));

        assertNull(coercer.coerce(null, String.class),
            "null must keep its built-in semantics and not hit custom rules");
        assertEquals("obj", coercer.coerce("x", String.class));
    }

    @Test
    void registerIfAbsentAddsMissingRule() {
        coercer.registerIfAbsent(new CoerceRule<>(
            String.class, Integer.class, s -> 42));

        assertEquals(42, coercer.coerce("x", Integer.class));
    }

    @Test
    void registerIfAbsentDoesNotOverwriteExistingRule() {
        coercer.register(new CoerceRule<>(
            String.class, Integer.class, s -> 1));
        coercer.registerIfAbsent(new CoerceRule<>(
            String.class, Integer.class, s -> 2));

        assertEquals(1, coercer.coerce("x", Integer.class),
            "an existing exact rule must keep priority");
    }

    @Test
    void concurrentRegisterAndCoerce() throws Exception {
        CoercerImpl c = new CoercerImpl();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 500; i++) {
                            c.register(new CoerceRule<>(
                                String.class, Integer.class, Integer::parseInt));
                            c.registerIfAbsent(new CoerceRule<>(
                                String.class, Long.class, Long::parseLong));
                            assertEquals(Integer.valueOf(42), c.coerce("42", Integer.class));
                        }
                    } catch (Throwable ex) {
                        error.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "concurrent register/coerce must complete within 10s");
        } finally {
            pool.shutdownNow();
        }
        assertNull(error.get(), "concurrent register/coerce failure: " + error.get());
        assertEquals(Integer.valueOf(42), c.coerce("42", Integer.class),
            "rules registered concurrently must all be visible");
    }

    @Test
    void coercesBetweenNumberTypes() {
        assertEquals(Integer.valueOf(12), coercer.coerce(12L, Integer.class));
        assertEquals(Long.valueOf(12), coercer.coerce(12, Long.class));
        assertEquals(Double.valueOf(3.0), coercer.coerce(3, Double.class));
    }

    @Test
    void coercesFromBooleanString() {
        assertTrue(coercer.coerce("true", Boolean.class));
        assertFalse(coercer.coerce("false", Boolean.class));
    }

    @Test
    void coercesBooleanFromAlternativeStrings() {
        assertTrue(coercer.coerce("yes", Boolean.class));
        assertTrue(coercer.coerce("YES", Boolean.class));
        assertTrue(coercer.coerce("on", Boolean.class));
        assertTrue(coercer.coerce("ON", Boolean.class));
        assertTrue(coercer.coerce("1", Boolean.class));
        assertFalse(coercer.coerce("no", Boolean.class));
        assertFalse(coercer.coerce("off", Boolean.class));
        assertFalse(coercer.coerce("0", Boolean.class));
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce("garbage", Boolean.class));
    }

    @Test
    void coercesBooleanFromNumber() {
        assertTrue(coercer.coerce(1, Boolean.class));
        assertTrue(coercer.coerce(42, Boolean.class));
        assertTrue(coercer.coerce(-1, Boolean.class));
        assertFalse(coercer.coerce(0, Boolean.class));
        assertFalse(coercer.coerce(0.0, Boolean.class));
        assertTrue(coercer.coerce(1.5, Boolean.class));
    }

    @Test
    void coercesDecimalToIntegerWithTruncation() {
        assertEquals(Integer.valueOf(3), coercer.coerce("3.14", Integer.class));
        assertEquals(Long.valueOf(3), coercer.coerce("3.14", Long.class));
    }

    @Test
    void coercesBigDecimalAndBigIntegerBidirectionally() {
        assertEquals(new BigInteger("42"), coercer.coerce(new BigDecimal("42"), BigInteger.class));
        assertEquals(new BigDecimal("42"), coercer.coerce(BigInteger.valueOf(42), BigDecimal.class));
    }

    @Test
    void rejectsInvalidNumericString() {
        assertThrows(IllegalArgumentException.class, () ->
            coercer.coerce("not-a-number", Integer.class));
    }

    @Test
    void defaultsAllPrimitivesToZero() {
        assertEquals((byte) 0, coercer.coerce(null, byte.class));
        assertEquals((short) 0, coercer.coerce(null, short.class));
        assertEquals(0, coercer.coerce(null, int.class));
        assertEquals(0L, coercer.coerce(null, long.class));
        assertEquals(0f, coercer.coerce(null, float.class));
        assertEquals(0d, coercer.coerce(null, double.class));
        assertEquals('\0', coercer.coerce(null, char.class));
        assertEquals(false, coercer.coerce(null, boolean.class));
    }

    // ====================== regression fixes ======================

    @Test
    void floatingToBigIntegerRejectsNonFiniteAndOverflow() {
        // longValue() used to silently saturate: 1e30 → Long.MAX_VALUE,
        // Infinity → Long.MAX_VALUE, NaN → 0. Finite floats now convert
        // exactly through the decimal representation.
        BigInteger tenPow30 = new BigInteger("1000000000000000000000000000000");
        assertEquals(BigInteger.valueOf(5), coercer.coerce(5.5, BigInteger.class));
        assertEquals(tenPow30, coercer.coerce(1e30, BigInteger.class));
        assertEquals(tenPow30, coercer.coerce("1e30", BigInteger.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(Double.POSITIVE_INFINITY, BigInteger.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(Double.NaN, BigInteger.class));
    }

    @Test
    void doubleFloatOverflowLiteralsRejected() {
        // parseDouble("1e400") → Infinity was returned silently; now it fails
        // loudly like the integral overflow paths do.
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("1e400", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("1e400", Float.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(new BigDecimal("1e400"), Double.class));
    }

    @Test
    void stringNaNRejectedForFloatingTargets() {
        // "NaN" used to parse into Double.NaN silently (then blowing up in
        // stringify or coercing to false). It is now rejected like Infinity:
        // a config value can never silently become NaN.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("NaN", Double.class));
        assertTrue(ex.getCause() != null
                && ex.getCause().getMessage().contains("not a finite number"),
            "NaN rejection must name the reason, got: " + ex.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("NaN", Float.class));
        // parseDouble accepts case variants ("nan"/"NAN"), so the value-based
        // check rejects them too — symmetric with case-insensitive Infinity.
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("nan", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("NAN", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("+NaN", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("-NaN", Double.class));
        // Normal finite numbers are unaffected.
        assertEquals(Double.valueOf(1.5), coercer.coerce("1.5", Double.class));
        assertEquals(Float.valueOf(2f), coercer.coerce("2", Float.class));
    }

    @Test
    void stringInfinitySpellingsRejectedForFloatingTargets() {
        // parseDouble("Infinity"/"-Infinity") → ±Infinity, rejected the same
        // way as overflow literals ("1e400") — both spellings, both targets.
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("Infinity", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("-Infinity", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("+Infinity", Double.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("Infinity", Float.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("-Infinity", Float.class));
    }

    @Test
    void nanNumberCoercesToFalseBoolean() {
        // Verified: (int) Double.NaN == 0, so the Number→boolean rule
        // (intValue() != 0) makes NaN falsy — like any other zero. The string
        // "NaN" can no longer reach this path (string→Double now rejects it),
        // but an actual Double.NaN value keeps this documented behavior.
        // (Infinity is a different case: (int) +Infinity saturates to
        // Integer.MAX_VALUE, so it is truthy — unchanged by this fix.)
        assertFalse(coercer.coerce(Double.NaN, boolean.class));
        assertFalse(coercer.coerce(Double.NaN, Boolean.class));
    }

    @Test
    void optionalPrimitivesNullAndOverflow() {
        // null → empty() (the entry's dead v == null branch showed intent);
        // oversized values must not wrap through intValue()/longValue().
        assertEquals(OptionalInt.empty(), coercer.coerce(null, OptionalInt.class));
        assertEquals(OptionalLong.empty(), coercer.coerce(null, OptionalLong.class));
        assertEquals(OptionalDouble.empty(), coercer.coerce(null, OptionalDouble.class));
        assertEquals(Optional.empty(), coercer.coerce(null, Optional.class));
        assertEquals(OptionalInt.of(5), coercer.coerce(5, OptionalInt.class));
        assertEquals(OptionalLong.of(5L), coercer.coerce("5", OptionalLong.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(3_000_000_000L, OptionalInt.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce("not-a-number", OptionalLong.class));
    }

    @Test
    void numericStringsAreTrimmed() {
        // Consistent with Boolean/Duration, which trim their inputs.
        assertEquals(12, coercer.coerce(" 12 ", Integer.class));
        assertEquals(12L, coercer.coerce(" 12 ", Long.class));
        assertEquals(1.5d, coercer.coerce(" 1.5 ", Double.class));
        assertEquals(new BigDecimal("12.5"), coercer.coerce(" 12.5 ", BigDecimal.class));
        assertEquals(BigInteger.valueOf(12), coercer.coerce(" 12 ", BigInteger.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(" 12x ", Integer.class));
    }

    @Test
    void characterCoercionUsesCodePointForNumbers() {
        // Numeric sources follow the (char) cast semantics: 65 → 'A', not
        // the decimal string's first character '6'. String sources keep the
        // first-character behavior.
        assertEquals('A', coercer.coerce(65, Character.class));
        assertEquals('\u0005', coercer.coerce(5.5, Character.class));
        assertEquals('a', coercer.coerce("ab", Character.class));
        assertEquals('a', coercer.coerce('a', Character.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(70_000, Character.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(-1, Character.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(Double.NaN, Character.class));
    }
}
