package com.jujin.freeway.commons.logging;

import java.util.Map;

/**
 * The application-side homes for log keys, supplied by the boot layer —
 * commons consumes the contract, boot owns the knowledge. The log cascade
 * reads values from (highest first): system properties, environment, the
 * dedicated {@code freeway-log.properties}, then {@link #applicationValues()}
 * (the application's main config files), then {@link #presetValues()} (the
 * active environment preset — the lowest precedence).
 *
 * <p>No provider on the classpath (a bare {@code Freeway.create} container
 * without boot) degenerates to the dedicated file plus -D/env only.
 *
 * <p>Both maps contain only {@code freeway.log.*} keys: the application's
 * config files are its whole config, and arbitrary dotted keys must never
 * surface as phantom logger names in the per-logger level enumeration.
 */
public interface LogConfigHomes {

    /** {@code freeway.log.*} keys from the application's main config files —
     *  the boot cascade's classpath file baseline ({@code application.properties} /
     *  {@code application.json} plus the active profile variants), so a log
     *  key resolves identically no matter which cascade reads it. */
    Map<String, String> applicationValues();

    /** {@code freeway.log.*} keys from the active environment preset
     *  ({@code freeway.preset} bootstrap key) — ranked below the application
     *  files. Empty when no preset is declared. */
    Map<String, String> presetValues();
}
