package com.jujin.freeway.commons.coercion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoercerDefaultTest {
    private final CoercerDefault coercer = new CoercerDefault();

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
        assertFalse(coercer.supports(Integer.class, java.util.regex.Pattern.class));
        assertFalse(coercer.supports(Color.class, java.util.regex.Pattern.class));
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
        java.math.BigInteger huge = new java.math.BigInteger("99999999999999999999");
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(huge, Integer.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(huge, Short.class));
        assertThrows(IllegalArgumentException.class,
            () -> coercer.coerce(huge, Long.class),
            "BigInteger beyond Long range must throw, not wrap");
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
    void supportsWithCustomRule() {
        CoercerDefault c = new CoercerDefault()
            .register(new CoerceRule<>(String.class, Duration.class, Duration::parse));

        assertTrue(c.supports(String.class, Duration.class));
        // Duration is a built-in scalar target — any source can coerce to it
        assertTrue(c.supports(Integer.class, Duration.class));
    }

    @Test
    void supportsWithCompatibleSourceType() {
        // CharSequence -> Duration 规则，String（子类）也应通过
        CoercerDefault c = new CoercerDefault()
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

    // --- supported tests ---

    @Test
    void supportedIncludesCustomRules() {
        CoercerDefault c = new CoercerDefault()
            .register(new CoerceRule<>(String.class, Duration.class, Duration::parse));

        Map<Class<?>, Set<Class<?>>> result = c.supported();
        assertTrue(result.containsKey(Duration.class));
        assertTrue(result.get(Duration.class).contains(String.class));
    }

    @Test
    void supportedIncludesBuiltins() {
        Map<Class<?>, Set<Class<?>>> result = coercer.supported();

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
        Map<Class<?>, Set<Class<?>>> result = coercer.supported();
        assertTrue(result.containsKey(Duration.class));
        assertTrue(result.get(Duration.class).contains(Object.class));
    }

    @Test
    void supportedIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () ->
            coercer.supported().put(Integer.class, Set.of()));
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
}
