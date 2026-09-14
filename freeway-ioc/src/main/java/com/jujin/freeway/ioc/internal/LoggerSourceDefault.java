package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.LoggerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The container's built-in {@link LoggerSource}: hands out SLF4J loggers.
 * A formal singleton class (not an inline anonymous implementation) so
 * {@link ContainerImpl} wires one stable instance ({@link #INSTANCE}) for
 * the {@code LoggerSource} builtin.
 */
final class LoggerSourceDefault implements LoggerSource {

    static final LoggerSourceDefault INSTANCE = new LoggerSourceDefault();

    private LoggerSourceDefault() {
    }

    @Override
    public Logger get(Class<?> ownerType) {
        return LoggerFactory.getLogger(ownerType);
    }

    @Override
    public Logger get(String name) {
        return LoggerFactory.getLogger(name);
    }
}
