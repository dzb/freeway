package com.jujin.freeway.commons.logging;

import java.util.Set;

/**
 * The logging configuration surface, published for the layers that must know it without owning it.
 *
 * <p>Logging is the only configuration the infrastructure modules own, and {@code commons} owns
 * it; {@code boot} is the module present in every application, so it declares this surface to the
 * unknown-key check and exposes the classpath subset of it through {@link LogConfigSource}.
 *
 * <p>Names, not constants: logging is configured through {@code -D}, environment variables or
 * {@code freeway-logging.properties}, never by referencing a constant — the table itself stays
 * package-private in {@link LogKeys}.
 */
public final class LogConfig {

    /** The namespace every logging key lives under. */
    public static final String PREFIX = LogKeys.PREFIX;

    /** The per-file family's prefix ({@code freeway.log.file.}): the dynamic
     *  half of {@link #PREFIX}, admitted by boot as unlistable. */
    public static final String FILE_PREFIX = LogKeys.FILE_PREFIX;

    /** The fixed key names under {@link #PREFIX}. The per-file keys
     *  ({@code freeway.log.file.<name>.path}) are dynamic by construction and stay unlisted —
     *  boot admits {@link #FILE_PREFIX}, so a legitimate named file is never reported,
     *  while a near-match typo of a fixed key still gets its fix. */
    public static Set<String> knownKeys() {
        return LogKeys.knownKeys();
    }

    private LogConfig() {}
}
