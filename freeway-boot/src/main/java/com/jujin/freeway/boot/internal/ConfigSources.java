package com.jujin.freeway.boot.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The loaded cascade inputs: one flat {@code key=value} map per source, plus
 * the active profiles. This is what {@link ConfigLoaderImpl} produces and what
 * {@link AppConfigDefault} turns into symbol providers, one per tier.
 *
 * <p>The maps stay separate instead of arriving pre-merged because the symbol
 * chain resolves them tier by tier: a pre-merged map could not tell a consumer
 * which tier answered. The field names are the tiers —
 * {@link com.jujin.freeway.ioc.symbol.SymbolProvider#TIER_CLI cli} →
 * {@code TIER_CLI}, {@code environment} → {@code TIER_ENV}, {@code files} →
 * {@code TIER_FILES}.
 */
public record ConfigSources(
    Map<String, String> cli,
    Map<String, String> environment,
    Map<String, String> files,
    List<String> profiles
) {

    public ConfigSources {
        cli = Map.copyOf(Objects.requireNonNull(cli, "cli"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        files = Map.copyOf(Objects.requireNonNull(files, "files"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
    }
}
