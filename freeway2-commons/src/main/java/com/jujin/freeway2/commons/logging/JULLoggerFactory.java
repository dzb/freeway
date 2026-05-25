package com.jujin.freeway2.commons.logging;

import org.slf4j.ILoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

public final class JULLoggerFactory implements ILoggerFactory {
    private final ConcurrentMap<String, JULLoggerAdapter> loggerMap = new ConcurrentHashMap<>();

    @Override
    public org.slf4j.Logger getLogger(String name) {
        return loggerMap.computeIfAbsent(name, key -> new JULLoggerAdapter(Logger.getLogger(key)));
    }
}
