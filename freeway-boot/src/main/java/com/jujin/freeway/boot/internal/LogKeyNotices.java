package com.jujin.freeway.boot.internal;

import com.jujin.freeway.commons.logging.LogConfig;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup notices for logging keys that cannot take effect where they were
 * declared.
 *
 * <p>Logging configures itself before any container exists, so it reads
 * {@code -D}, the environment and the classpath config files — never the CLI
 * tier, never the filesystem override files, and never any file for the four
 * keys read at class-load time. A key in one of those places still resolves in
 * the symbol chain, which is exactly the trap: the configuration looks applied
 * and every logger stays on its default. These notices name the key, its
 * value, the declaring source, and the channels that do work.</p>
 */
final class LogKeyNotices {

    private static final Logger LOG = LoggerFactory.getLogger(LogKeyNotices.class);

    private LogKeyNotices() {}

    /**
     * Names every {@code freeway.log.*} key declared by a tier the logging side
     * never reads.
     *
     * @param alreadyNamed when non-null, each {@code origin + key} is reported
     *                     at most once (hot reload re-runs this); null reports
     *                     every occurrence, for the one-shot startup path
     */
    static void unread(String origin, Map<String, String> values, Set<String> alreadyNamed) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(LogConfig.PREFIX)) {
                continue;
            }
            if (alreadyNamed != null && !alreadyNamed.add(origin + '\u0000' + key)) {
                continue;
            }
            LOG.warn(
                "Log key {} = {} is declared by {}, but logging reads only -D, "
                    + "environment variables and the classpath config files — "
                    + "this value has no effect",
                key, entry.getValue(), origin);
        }
    }

    /**
     * Names a class-load-only log key declared in a file: those keys
     * ({@link LogConfig#classLoadKeys()}) are read before any file is parsed,
     * so even the classpath config files cannot supply them.
     */
    static void classLoadOnly(String origin, Map<String, String> values) {
        Set<String> classLoadOnly = LogConfig.classLoadKeys();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!classLoadOnly.contains(entry.getKey())) {
                continue;
            }
            LOG.warn(
                "Log key {} = {} is declared by {}, but it is read at class-load "
                    + "time — provide it as -D or an environment variable; a file "
                    + "cannot supply it",
                entry.getKey(), entry.getValue(), origin);
        }
    }
}
