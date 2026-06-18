package com.jujin.freeway.commons.coercion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
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

    // --- canCoerce tests ---

    @Test
    void canCoerceBuiltinScalars() {
        assertTrue(coercer.canCoerce(String.class, Integer.class));
        assertTrue(coercer.canCoerce(String.class, int.class));
        assertTrue(coercer.canCoerce(String.class, Long.class));
        assertTrue(coercer.canCoerce(String.class, Double.class));
        assertTrue(coercer.canCoerce(String.class, Boolean.class));
        assertTrue(coercer.canCoerce(String.class, boolean.class));
        assertTrue(coercer.canCoerce(String.class, Character.class));
        assertTrue(coercer.canCoerce(String.class, char.class));
        assertTrue(coercer.canCoerce(String.class, BigDecimal.class));
        assertTrue(coercer.canCoerce(String.class, BigInteger.class));
        assertTrue(coercer.canCoerce(String.class, LocalDate.class));
        assertTrue(coercer.canCoerce(String.class, Instant.class));
        assertTrue(coercer.canCoerce(String.class, UUID.class));
        assertTrue(coercer.canCoerce(Integer.class, String.class));
        assertTrue(coercer.canCoerce(Integer.class, Long.class));
        assertTrue(coercer.canCoerce(Integer.class, double.class));
        assertTrue(coercer.canCoerce(Double.class, Integer.class));
        assertTrue(coercer.canCoerce(BigDecimal.class, BigInteger.class));
    }

    @Test
    void canCoerceNullToAny() {
        assertTrue(coercer.canCoerce(Void.class, String.class));
        assertTrue(coercer.canCoerce(Void.class, int.class));
        assertTrue(coercer.canCoerce(Void.class, Boolean.class));
    }

    @Test
    void canCoerceIdentityOrSubtype() {
        assertTrue(coercer.canCoerce(String.class, String.class));
        assertTrue(coercer.canCoerce(Integer.class, Object.class));
        assertTrue(coercer.canCoerce(Integer.class, Number.class));
        assertTrue(coercer.canCoerce(Integer.class, Comparable.class));
    }

    @Test
    void canCoerceEnum() {
        assertTrue(coercer.canCoerce(String.class, Color.class));
        assertTrue(coercer.canCoerce(String.class, Status.class));
    }

    @Test
    void cannotCoerceUnsupported() {
        assertFalse(coercer.canCoerce(Integer.class, java.nio.file.Path.class));
        assertFalse(coercer.canCoerce(Color.class, java.nio.file.Path.class));
    }

    @Test
    void canCoerceWithCustomRule() {
        CoercerDefault c = new CoercerDefault()
            .register(new CoerceRule<>(String.class, Duration.class, Duration::parse));

        assertTrue(c.canCoerce(String.class, Duration.class));
        assertFalse(c.canCoerce(Integer.class, Duration.class));
    }

    @Test
    void canCoerceWithCompatibleSourceType() {
        // CharSequence -> Duration 规则，String（子类）也应通过
        CoercerDefault c = new CoercerDefault()
            .register(new CoerceRule<>(CharSequence.class, Duration.class,
                s -> Duration.parse(s.toString())));

        assertTrue(c.canCoerce(String.class, Duration.class));
        assertTrue(c.canCoerce(StringBuilder.class, Duration.class));
        assertTrue(c.canCoerce(CharSequence.class, Duration.class));
    }

    @Test
    void canCoerceRejectsNullParams() {
        assertThrows(NullPointerException.class, () -> coercer.canCoerce(null, String.class));
        assertThrows(NullPointerException.class, () -> coercer.canCoerce(String.class, null));
    }

    // --- conversions tests ---

    @Test
    void conversionsIncludesCustomRules() {
        CoercerDefault c = new CoercerDefault()
            .register(new CoerceRule<>(String.class, Duration.class, Duration::parse));

        Map<Class<?>, Set<Class<?>>> result = c.conversions();
        assertTrue(result.containsKey(Duration.class));
        assertTrue(result.get(Duration.class).contains(String.class));
    }

    @Test
    void conversionsIncludesBuiltins() {
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
    void conversionsEmptyWhenNoCustomRules() {
        // 没有自定义规则时，conversions 不包含非内置类型
        Map<Class<?>, Set<Class<?>>> result = coercer.conversions();
        assertFalse(result.containsKey(Duration.class));
    }

    @Test
    void conversionsIsUnmodifiable() {
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
        assertFalse(coercer.coerce("garbage", Boolean.class));
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
