package com.jujin.freeway2.ioc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceIdTest {

    @Test
    void testTrimmedServiceId() {
        assertEquals("abc", ServiceId.of("  abc  ").value());
    }

    @Test
    void testUntrimmedServiceId() {
        assertEquals("abc", ServiceId.of("abc").value());
    }

    @Test
    void testBlankServiceIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> ServiceId.of("   "));
    }
}
