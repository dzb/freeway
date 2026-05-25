package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class JULLoggerFactoryTest {

    @Test
    void returnsSameInstanceForRepeatedNames() {
        JULLoggerFactory factory = new JULLoggerFactory();

        var logger1 = factory.getLogger("com.example.Service");
        var logger2 = factory.getLogger("com.example.Service");

        assertSame(logger1, logger2);
    }

    @Test
    void returnsDistinctAdaptersForDistinctNames() {
        JULLoggerFactory factory = new JULLoggerFactory();

        var logger1 = factory.getLogger("com.example.A");
        var logger2 = factory.getLogger("com.example.B");

        assertInstanceOf(JULLoggerAdapter.class, logger1);
        assertInstanceOf(JULLoggerAdapter.class, logger2);
    }
}
