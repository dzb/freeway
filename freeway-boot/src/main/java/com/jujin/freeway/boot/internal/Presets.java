package com.jujin.freeway.boot.internal;

import com.jujin.freeway.commons.util.EnvKeys;

import java.util.Map;

/**
 * Environment presets: one bootstrap key ({@code freeway.preset}) selects a
 * bundle of opinionated defaults for a standard deployment environment.
 *
 * <p>A preset is the lowest-precedence tier — it fills only what no higher
 * source set (files, environment, system properties all outrank it), so it
 * can never override an explicit value. The key itself is bootstrap-only:
 * read from {@code -Dfreeway.preset} or {@code FREEWAY_PRESET}, never from
 * config files — the preset configures the very channels a config file
 * would arrive through (a chicken-and-egg problem, so the JVM property and
 * the raw environment variable are the only sources).
 *
 * <p>Where it connects — all internal wiring, the user's interface is the
 * bootstrap key alone (documented in the config guide):
 * <ul>
 *   <li>{@link AppConfigDefault} validates the declared name at construction
 *       (unknown names fail startup) and serves the bundle as the lowest
 *       {@code SymbolProvider} tier (order 25);</li>
 *   <li>{@link AppLogSource} exposes the bundle's {@code freeway.log.*}
 *       subset to the bootstrap JUL log cascade — {@code docker}'s
 *       {@code log.file=off} works because both cascades read this bundle.</li>
 * </ul>
 *
 * <p>{@code docker} targets container platforms generally — docker, k8s,
 * ECS — because a containerized app's real differences from the defaults are
 * the binding address and stdout-only logging; k8s probes are fixed routes
 * and carry no preset values of their own. {@code local} exists to state the
 * dev intent explicitly; its bundle is empty because the defaults already
 * are the dev-friendly values.
 */
public final class Presets {

    /** Bootstrap-only selector key ({@code -Dfreeway.preset}). */
    public static final String KEY = "freeway.preset";
    /** Environment form of the selector ({@code FREEWAY_PRESET}); a custom
     *  {@code freeway.env.prefix} deliberately does not apply to it. */
    public static final String ENV_KEY = EnvKeys.name(KEY);

    private static final Map<String, Map<String, String>> BUNDLES = Map.of(
        "local", Map.of(),
        "docker", Map.of(
            "freeway.http.server.host", "0.0.0.0",
            "freeway.log.file", "off"));

    private Presets() {}

    /** The bundle for {@code name}; null when the name is unknown. */
    public static Map<String, String> bundle(String name) {
        if (name == null) {
            return null;
        }
        return BUNDLES.get(name.strip());
    }

    /** The declared preset name: system property first, then the
     *  {@code FREEWAY_PRESET} environment variable; null when neither is set. */
    public static String declared() {
        return EnvKeys.bootstrap(KEY);
    }

    /**
     * The active preset's bundle, or an empty map when no preset is declared
     * (or the declared name is unknown). This is the tier the symbol chain
     * serves: the loader resolves it once, so no consumer has to read the
     * bootstrap key again.
     */
    public static Map<String, String> activeBundle() {
        Map<String, String> bundle = bundle(declared());
        return bundle == null ? Map.of() : bundle;
    }

    /** Validates a preset name at startup; an unknown name fails naming the
     *  valid choices instead of silently doing nothing. */
    public static void validate(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (!BUNDLES.containsKey(name.strip())) {
            throw new IllegalArgumentException(
                KEY + " must be one of " + BUNDLES.keySet() + ": " + name);
        }
    }
}
