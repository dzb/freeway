package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend-<code>type</code> config guard. The {@code freeway.cloud.*.type}
 * keys select an external backend (Nacos/Consul/K8s/S3/Vault/...) delivered
 * by a freeway-ext adapter; the built-in implementations are local-only and
 * ignore them. A non-{@code local} value without the adapter would be
 * silently dropped, so every local provider warns once (mirroring the
 * {@code @Marker} fallback's non-silent principle) instead of pretending the
 * setting took effect.
 */
public final class BackendTypeGuard {

    private static final Logger LOG = LoggerFactory.getLogger(BackendTypeGuard.class);

    private BackendTypeGuard() {}

    /**
     * Warns when {@code typeKey} resolves to a non-blank value other than
     * {@code local} — i.e. the user asked for an ext backend that is not
     * installed. Silent when the value is blank or {@code local}.
     */
    public static void warnIfExternal(SymbolSource symbols, String typeKey, String subsystem) {
        String type = symbols.resolve(typeKey, "");
        if (type != null && !type.isBlank() && !"local".equalsIgnoreCase(type.trim())) {
            LOG.warn(
                "freeway.cloud.{} type '{}' requires the matching freeway-ext adapter;"
                    + " no adapter is installed, using the built-in local implementation"
                    + " and ignoring the setting",
                subsystem,
                type.trim());
        }
    }
}
