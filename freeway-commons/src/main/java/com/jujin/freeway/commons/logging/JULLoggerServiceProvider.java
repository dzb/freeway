package com.jujin.freeway.commons.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;
import java.util.logging.Handler;
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
        installFormatters();
    }

    private static void installFormatters() {
        String format = System.getProperty("freeway.log.format",
                System.getenv("FREEWAY_LOG_FORMAT"));

        // "simple" — keep JUL native SimpleFormatter
        if (format != null && "simple".equalsIgnoreCase(format.strip())) {
            return;
        }

        if (format != null && !format.isBlank()) {
            Logger.getLogger("com.jujin.freeway.commons.logging")
                    .warning("Unknown freeway.log.format '" + format.strip()
                            + "' — ignoring");
        }

        Logger root = Logger.getLogger("");
        if (root == null) {
            return;
        }

        JULConsoleFormatter formatter = new JULConsoleFormatter();
        for (Handler h : root.getHandlers()) {
            h.setFormatter(formatter);
        }
    }
}
