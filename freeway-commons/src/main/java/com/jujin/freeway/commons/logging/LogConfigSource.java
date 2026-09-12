package com.jujin.freeway.commons.logging;

import java.util.Map;

/**
 * The application-side source of log config values, supplied by the boot
 * layer — commons consumes the contract, boot owns the knowledge. The log
 * cascade reads values from (highest first): system properties, environment,
 * the dedicated {@code freeway-logging.properties}, then {@link #values()} (the
 * application's main config files, merged in the provider's own precedence).
 *
 * <p>No provider on the classpath (a bare {@code Freeway.create} container
 * without boot) degenerates to the dedicated file plus -D/env only.
 *
 * <p>The map contains only {@code freeway.log.*} keys: the application's
 * config files are its whole config, and arbitrary dotted keys must never
 * surface as phantom logger names in the per-logger level enumeration.
 */
public interface LogConfigSource {

    /** {@code freeway.log.*} keys from the application side — the boot
     *  cascade's classpath file baseline ({@code application.properties} /
     *  {@code application.json} plus the active profile variants), already
     *  merged in the provider's own precedence. Empty without boot. */
    Map<String, String> values();
}
