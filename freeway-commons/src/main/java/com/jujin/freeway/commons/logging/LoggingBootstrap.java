package com.jujin.freeway.commons.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingBootstrap {
    private static final String JUL_PROVIDER = JULLoggerServiceProvider.class.getName();

    private LoggingBootstrap() {
    }

    public static boolean autoConfigure() {
        if (System.getProperty("slf4j.provider") != null) {
            return false;
        }
        if (hasExternalLogger()) {
            return false;
        }
        System.setProperty("slf4j.provider", JUL_PROVIDER);
        return true;
    }

    public static Logger logger(Class<?> ownerType) {
        autoConfigure();
        return LoggerFactory.getLogger(ownerType);
    }

    public static Logger logger(String name) {
        autoConfigure();
        return LoggerFactory.getLogger(name);
    }

    private static boolean hasExternalLogger() {
        if (classExists("ch.qos.logback.classic.spi.LogbackServiceProvider")) {
            return true;
        }
        if (classExists("org.apache.logging.slf4j.Log4jLoggerFactory")) {
            return true;
        }
        if (classExists("org.slf4j.reload4j.Reload4jLoggerFactory")) {
            return true;
        }
        return classExists("org.slf4j.simple.SimpleServiceProvider");
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, LoggingBootstrap.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
