package com.jujin.freeway.commons.logging;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Every logging key name in one place: the {@code freeway.log.*} namespace plus the
 * {@code -D}-only {@link #APP_NAME}, which names the default log file and is the one
 * key logging owns outside the framework prefix.
 *
 * <p>These names used to live as 23 string literals across five classes, so a
 * rename could not break the build — it just stopped matching, quietly, in
 * whichever reader was missed. The table is the single home: a key that changes
 * changes here, and every reader follows.</p>
 *
 * <p>Package-private on purpose: the keys are the framework's own, and callers
 * configure them through {@code -D}, environment variables or
 * {@code freeway-logging.properties} — never by referencing a constant.</p>
 */
final class LogKeys {

    /** Namespace prefix — the filter that admits a key into the log config, and
     *  the exclusion that keeps {@code freeway.log.*} from being read as logger
     *  levels. */
    static final String PREFIX = "freeway.log.";

    /** The default log file's base name ({@code logs/<app.name>.log}). Read as a
     *  plain {@code -D} at class-load time, so it never enters the config cascade
     *  and is deliberately outside {@link #PREFIX}: it is the application's name,
     *  not a logging option. */
    static final String APP_NAME = "app.name";

    /** Root level for every logger ({@code INFO} when unset). */
    static final String LEVEL = "freeway.log.level";

    /** Formatter mode: {@code auto} (Freeway formatters) or {@code simple}. */
    static final String FORMAT = "freeway.log.format";

    /** Console color mode — {@code -D}/env only, read at class-load time. */
    static final String COLOR = "freeway.log.color";

    /** Whether the MDC block is rendered — {@code -D}/env only. */
    static final String MDC = "freeway.log.mdc";

    /** MDC key order — {@code -D}/env only. Unset means alphabetical. */
    static final String MDC_PRIORITY = "freeway.log.mdc.priority";

    /** Whether the source class/method is resolved per record — {@code -D}/env only. */
    static final String CALLER_INFO = "freeway.log.caller-info";

    /** Whether the console handler is installed at all. */
    static final String CONSOLE_ENABLED = "freeway.log.console.enabled";

    /** Console handler level ({@code INFO} when unset). */
    static final String CONSOLE_LEVEL = "freeway.log.console.level";

    /** Comma-separated list of additional named log files. */
    static final String FILES = "freeway.log.files";

    /** The default log file key ({@code auto} → {@code logs/<app.name>.log}). */
    static final String FILE = "freeway.log.file";

    /** Prefix of the per-file keys, both the default file and named ones
     *  ({@code freeway.log.file.<name>.path}). */
    static final String FILE_PREFIX = FILE + ".";

    /** Rotation/compression suffixes, appended to {@link #FILE} or to a named
     *  file's prefix — one spelling shared by every reader. */
    static final String SUFFIX_PATH = ".path";
    static final String SUFFIX_LOGGER = ".logger";
    static final String SUFFIX_LEVEL = ".level";
    static final String SUFFIX_MAX_SIZE = ".max-size";
    static final String SUFFIX_MAX_HISTORY = ".max-history";
    static final String SUFFIX_COMPRESS = ".compress";
    static final String SUFFIX_FLUSH_INTERVAL = ".flush-interval";

    /** The default file's rotation/compression keys, spelled out in full rather
     *  than composed from {@link #FILE} + a suffix: the docs-consistency test
     *  finds "a documented key no module reads" by scanning literals, and a
     *  composed name would look like a knob that does nothing. Named files
     *  ({@code FILE_PREFIX + <name> + SUFFIX_*}) have no such literal by
     *  construction and are documented by pattern. */
    static final String FILE_MAX_SIZE = "freeway.log.file.max-size";
    static final String FILE_MAX_HISTORY = "freeway.log.file.max-history";
    static final String FILE_COMPRESS = "freeway.log.file.compress";
    static final String FILE_FLUSH_INTERVAL = "freeway.log.file.flush-interval";

    /**
     * The fixed key names of {@link #PREFIX} — what the unknown-key vocabulary needs, without
     * publishing the constants. Read off this class, so the table and the list cannot drift:
     * a fragment ends with {@code '.'}, and {@link #APP_NAME} is outside the namespace.
     */
    static Set<String> knownKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Field field : LogKeys.class.getDeclaredFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (value != null && under(value, PREFIX) && !value.endsWith(".")) {
                    keys.add(value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                    "Cannot read log key constant " + field.getName()
                        + " — the vocabulary must be readable, not silently empty", e);
            }
        }
        return Set.copyOf(keys);
    }

    /**
     * Whether a key belongs to a namespace: equal to the prefix, or below it on a dot boundary —
     * the same fence {@code KnownKeys} applies (commons cannot depend on ioc, so the three lines
     * live here too). {@link #PREFIX} ends with a dot, which already makes {@code startsWith}
     * exact; the boundary form keeps the idiom single if that ever changes.
     */
    private static boolean under(String key, String prefix) {
        return key.equals(prefix)
            || key.startsWith(prefix.endsWith(".") ? prefix : prefix + ".");
    }

    private LogKeys() {}
}
