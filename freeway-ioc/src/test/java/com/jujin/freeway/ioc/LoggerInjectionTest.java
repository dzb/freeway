package com.jujin.freeway.ioc;
import com.jujin.freeway.ioc.annotation.Inject;

import com.jujin.freeway.ioc.annotation.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** LoggerInjectionTest: split from the former FreewayTest monolith (behavior-preserving move). */
class LoggerInjectionTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void loggerServiceAndInjectionUseOwningTypeByDefault() {
        Container container = Freeway.create();
        LoggerSource loggerSource = container.get(LoggerSource.class);

        assertEquals(LoggerFieldHolder.class.getName(), loggerSource.get(LoggerFieldHolder.class).getName());
        assertTrue(loggerSource.get(LoggerFieldHolder.class).isInfoEnabled());

        LoggerFieldHolder fieldHolder = container.create(LoggerFieldHolder.class);
        assertEquals(LoggerFieldHolder.class.getName(), fieldHolder.loggerName());

        LoggerCtorHolder ctorHolder = container.create(LoggerCtorHolder.class);
        assertEquals(LoggerCtorHolder.class.getName(), ctorHolder.loggerName());
    }

    @Test
    void loggerInjectionCanUseExplicitName() {
        Container container = Freeway.create();

        NamedLoggerHolder holder = container.create(NamedLoggerHolder.class);

        assertEquals("audit", holder.loggerName());
    }

    @Test
    void loggerFieldNotInjectedWithoutAnnotation() {
        Container container = Freeway.create();
        PlainLoggerHolder holder = container.create(PlainLoggerHolder.class);
        assertNull((Object) holder.logger,
                "Logger field without @Inject should not be injected");
    }
}
