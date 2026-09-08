package com.jujin.freeway.boot;

import java.util.Map;
import java.util.Set;

/**
 * Environment presets: one bootstrap key ({@code freeway.preset}) selects a
 * bundle of opinionated defaults for a standard deployment environment.
 *
 * <p>A preset is the lowest-precedence tier — it fills only what no higher
 * source set (files, environment, system properties all outrank it), so it
 * can never override an explicit value. The key itself is bootstrap-only:
 * read from {@code -Dfreeway.preset} or {@code FREEWAY_PRESET}, never from
 * config files. Read only inside boot — the symbol-chain tier (order 25)
 * and the log-home implementation resolve it identically by construction.
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
    /** Environment form of the selector; a custom {@code freeway.env.prefix}
     *  does not apply to it — use the system property instead. */
    public static final String ENV_KEY = "FREEWAY_PRESET";

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

    /** Valid preset names — for validation error messages. */
    public static Set<String> names() {
        return BUNDLES.keySet();
    }

    /** The active preset's value for {@code key}, or null. */
    public static String value(String key) {
        Map<String, String> active = bundle(declared());
        return active == null ? null : active.get(key);
    }

    /** The declared preset name: system property first, then environment;
     *  null when neither is set. Bootstrap sources only. */
    public static String declared() {
        String v = System.getProperty(KEY);
        if (v != null && !v.isBlank()) {
            return v.strip();
        }
        String e = System.getenv(ENV_KEY);
        if (e != null && !e.isBlank()) {
            return e.strip();
        }
        return null;
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
