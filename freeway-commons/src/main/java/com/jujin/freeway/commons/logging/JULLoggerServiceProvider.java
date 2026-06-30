package com.jujin.freeway.commons.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class JULLoggerServiceProvider implements SLF4JServiceProvider {
    private ILoggerFactory loggerFactory;
    private IMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.17";
    }

    @Override
    public void initialize() {
        loggerFactory = new JULLoggerFactory();
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new JULMDCAdapter();
        loadClasspathConfig();
        installFormatters();
    }

    /**
     * Loads {@code logging.properties} from the classpath root, if present.
     * Uses the existing LogManager so any previously set handlers/formatters
     * are preserved. Users can override with {@code -Djava.util.logging.config.file}.
     */
    private static void loadClasspathConfig() {
        if (System.getProperty("java.util.logging.config.file") != null) {
            return; // explicit override — user controls it
        }
        try (InputStream in = JULLoggerServiceProvider.class.getClassLoader()
                .getResourceAsStream("logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().updateConfiguration(in, null);
            }
        } catch (IOException e) {
            Logger.getLogger(JULLoggerServiceProvider.class.getName())
                    .warning("Failed to load logging.properties from classpath: " + e);
        }
    }

    private static void installFormatters() {
        String format = formatOverride();
        if (format != null) {
            String trimmed = format.strip();
            if ("simple".equalsIgnoreCase(trimmed)) {
                return; // user wants JUL native SimpleFormatter
            }
            if (!trimmed.isBlank()) {
                Logger.getLogger("com.jujin.freeway.commons.logging")
                        .warning("Unknown freeway.log.format '" + trimmed
                                + "' — ignoring");
            }
        }

        JULConsoleFormatter consoleFmt = new JULConsoleFormatter();
        JULFileFormatter fileFmt = new JULFileFormatter();
        Logger root = Logger.getLogger("");
        if (root == null) return;

        for (Handler h : root.getHandlers()) {
            if (h instanceof FileHandler || h instanceof JULFileHandler) {
                h.setFormatter(fileFmt);
            } else {
                h.setFormatter(consoleFmt);
            }
        }
    }

    private static String formatOverride() {
        String v = System.getProperty("freeway.log.format");
        if (v != null) return v;
        return System.getenv("FREEWAY_LOG_FORMAT");
    }
}
