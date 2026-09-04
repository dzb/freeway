package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.commons.config.ConfigSpec;

import java.util.Arrays;
import java.util.List;

/**
 * Parsing for the comma-separated list values this module puts on the wire and
 * in config: {@code freeway.cloud.events.peers}, {@code .allowed-types},
 * {@code secret.keys}, and the {@code x-baggage} / {@code x-principal-roles}
 * headers.
 *
 * <p>The four call sites used to spell this out inline, and the copies drifted:
 * some guarded {@code null}/blank, some did not; some trimmed, some did not.
 * A list-valued config key that is unset, set to {@code ""}, or set to
 * {@code " , ,"} must mean the same thing (no entries), or a peer list silently
 * changes meaning between an absent key and an empty one.
 *
 * <p>Deliberately not promoted to {@code freeway-commons}: it is four call
 * sites in one module, not a general-purpose text utility.
 */
public final class ConfigLists {

    private ConfigLists() {}

    /**
     * A {@link ConfigSpec} for a comma-separated key, parsed by
     * {@link #splitAndTrim(String)}.
     *
     * <p>The unchecked cast lives here rather than at every declaration:
     * {@code List<String>} has no class literal, so a list-valued spec cannot
     * be declared without one.
     */
    @SuppressWarnings("unchecked")
    public static ConfigSpec<List<String>> spec(String key, List<String> defaultValue) {
        return ConfigSpec.of(
            key,
            (Class<List<String>>) (Class<?>) List.class,
            defaultValue,
            ConfigLists::splitAndTrim);
    }

    /**
     * Splits a comma-separated value into trimmed, non-empty parts.
     *
     * @param raw the raw value; {@code null} or blank yields an empty list
     * @return an immutable list of entries, never {@code null}
     */
    public static List<String> splitAndTrim(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
