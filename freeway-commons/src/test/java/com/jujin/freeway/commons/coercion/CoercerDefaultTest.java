package com.jujin.freeway.commons.coercion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
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
        assertThrows(IllegalStateException.class, () ->
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
        assertThrows(IllegalStateException.class, () ->
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
