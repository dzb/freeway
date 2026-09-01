package com.jujin.freeway.cloud.secret;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic {@link SymbolProvider} backed by {@link SecretStore}: makes secrets
 * resolvable via {@code @Symbol("db.password")} (and any symbol lookup), with
 * secret-source priority over the config provider — declared via
 * {@link #order()}, independent of module install order.
 *
 * <p><b>Known collision surface.</b> This provider answers for <i>every</i>
 * name, and {@link SecretStore} implementations typically check the process
 * environment first ({@code db.password} → {@code DB_PASSWORD}). A symbol that
 * is not a secret can therefore resolve to an unrelated environment variable —
 * {@code path} picks up {@code PATH}, {@code user} picks up {@code USER}. Set
 * {@code freeway.cloud.secret.keys} to a comma-separated allowlist to confine
 * lookups to the names that really are secrets; an unset value keeps the
 * permissive default.
 *
 * <p>The allowlist is read from the system property directly, never through
 * {@code SymbolSource}: this provider participates in symbol resolution, so
 * routing its own configuration through it would recurse.
 */
public final class SecretSymbolSource implements SymbolProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SecretSymbolSource.class);

    private final SecretStore store;
    /** {@code null} = answer for any name (the documented default). */
    private final List<String> allowedKeys;

    public SecretSymbolSource(SecretStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.allowedKeys = parseAllowedKeys(
            System.getProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_KEYS));
        if (allowedKeys == null) {
            // Same visible-at-startup stance as PeerHub's ungated-mesh
            // warning: the permissive default answers for EVERY name, so any
            // symbol whose name matches an environment variable silently
            // resolves to it (path → PATH, user → USER), outranking config
            // files. Operators should see that they are on the sharp edge.
            LOG.warn("SecretSymbolSource has no allowlist (set {} as a comma-separated "
                    + "system property) — every symbol name is checked against the "
                    + "environment first, which can shadow config keys with "
                    + "unrelated variables",
                com.jujin.freeway.cloud.CloudConfigKeys.SECRET_KEYS);
        }
    }

    @Override
    public String lookup(String name) {
        if (allowedKeys != null && !allowedKeys.contains(name)) {
            return null; // not a declared secret — leave the name to the next provider
        }
        return store.get(name).orElse(null);
    }

    /**
     * Consulted between the framework's env tier (10) and file tier (20):
     * secrets win over every file-based source. Declared here — module
     * slots are not part of the ioc framework tiers.
     */
    @Override
    public int order() {
        return 15;
    }

    private static List<String> parseAllowedKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
