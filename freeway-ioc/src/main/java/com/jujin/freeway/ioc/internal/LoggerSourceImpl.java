package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.LoggerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link LoggerSource}: hands out SLF4J loggers. A formal class (not
 * an inline anonymous implementation) so the builtin follows the same
 * {@code XDefault} convention as {@link SymbolSourceDefault} and can be
 * overridden via a primary binding.
 */
final class LoggerSourceImpl implements LoggerSource {

    static final LoggerSourceImpl INSTANCE = new LoggerSourceImpl();

    private LoggerSourceImpl() {
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
