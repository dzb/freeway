package com.jujin.freeway.commons.logging;

/**
 * Every {@code freeway.log.*} key name in one place.
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

    /** The default file's rotation/compression keys, spelled once. */
    static final String FILE_MAX_SIZE = FILE + SUFFIX_MAX_SIZE;
    static final String FILE_MAX_HISTORY = FILE + SUFFIX_MAX_HISTORY;
    static final String FILE_COMPRESS = FILE + SUFFIX_COMPRESS;
    static final String FILE_FLUSH_INTERVAL = FILE + SUFFIX_FLUSH_INTERVAL;

    private LogKeys() {}
}
