package com.jujin.freeway.commons.util;

import java.util.Locale;

/**
 * The environment-variable spelling of config keys, and the resolution of the
 * bootstrap keys — the handful of settings that configure the configuration
 * system itself ({@code freeway.env.prefix}, {@code freeway.config.file}).
 *
 * <p><b>Spelling.</b> A key's environment name is the prefix plus the
 * upper-cased key with <em>dots</em> turned into underscores. That is the whole
 * rule: every other character — a hyphen above all — is an ordinary character,
 * carried through verbatim. {@code -} is not a separator and is never folded
 * into {@code .} or {@code _}: {@code key-store} and {@code key.store} are
 * different keys, and folding them would let one variable feed two of them.
 * A key that contains a hyphen therefore has a hyphen in its environment name;
 * whether a given shell or platform can set such a name is the operator's
 * concern (use {@code -D}, {@code env}, or a container's {@code -e}), not a
 * reason for the mapping to guess.
 *
 * <p><b>Bootstrap keys.</b> They cannot come from the cascade they configure,
 * so they have exactly two channels: the system property {@code -Dkey}, then
 * the environment variable {@code name(key)}. The {@code FREEWAY_} prefix is
 * fixed for them — honoring a custom prefix would require reading
 * {@code freeway.env.prefix} through the very mapping that prefix configures.
 */
public final class EnvKeys {

    /** The default namespace prefix for both spellings of a key. */
    public static final String DEFAULT_PREFIX = "FREEWAY_";

    /** The bootstrap key selecting the env-mapping prefix. */
    public static final String PREFIX_KEY = "freeway.env.prefix";

    private EnvKeys() {}

    /**
     * The env-mapping prefix in force: the declared {@link #PREFIX_KEY
     * bootstrap key}, or {@link #DEFAULT_PREFIX} when it is unset.
     *
     * <p>The one definition both directions of the mapping share — boot's
     * env-name→key mapping and the log cascade's key→env-name spelling — so
     * the two cannot drift into disagreeing about which variables exist.
     */
    public static String prefix() {
        String declared = bootstrap(PREFIX_KEY);
        return declared == null ? DEFAULT_PREFIX : declared;
    }

    /**
     * The environment name of a key under {@code prefix}:
     * {@code freeway.log.file.max-size} → {@code FREEWAY_LOG_FILE_MAX-SIZE}.
     *
     * <p>{@link #DEFAULT_PREFIX} is the namespace itself — the cascade maps
     * {@code FREEWAY_*} into {@code freeway.*}, so a key already carrying that
     * namespace is spelled {@code FREEWAY_<rest>}. With any other prefix the
     * key's own spelling is appended ({@code APP_} + {@code LOG_LEVEL}).
     */
    public static String name(String prefix, String key) {
        String upper = key.toUpperCase(Locale.ROOT).replace('.', '_');
        return DEFAULT_PREFIX.equals(prefix) ? upper : prefix + upper;
    }

    /** The environment name of a key under {@link #DEFAULT_PREFIX}. */
    public static String name(String key) {
        return name(DEFAULT_PREFIX, key);
    }

    /**
     * The declared value of a bootstrap key: the system property first, then
     * the {@code FREEWAY_}-prefixed environment variable. Blank counts as
     * unset and the value is stripped; {@code null} when neither channel set
     * it.
     */
    public static String bootstrap(String key) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property.strip();
        }
        String environment = System.getenv(name(key));
        return environment == null || environment.isBlank() ? null : environment.strip();
    }
}
