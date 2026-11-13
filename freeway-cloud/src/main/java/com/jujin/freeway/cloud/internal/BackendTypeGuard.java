package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend-<code>type</code> config guard. The {@code freeway.cloud.*.type}
 * keys select an external backend (Nacos/Consul/K8s/S3/Vault/...) provided by
 * a backend bound primary — an extension adapter (Consul ships in freeway-ext)
 * or the freeway-native fleet ({@code FleetRegistryModule}); the built-in
 * implementations are local-only and ignore them. When an external value is configured but the local binding is
 * still the active one, the value is silently dropped — so the local provider
 * warns once (mirroring the {@code @Marker} fallback's non-silent principle).
 * An adapter bound primary suppresses the warning because the setting is then
 * honored.
 */
public final class BackendTypeGuard {

    private static final Logger LOG = LoggerFactory.getLogger(BackendTypeGuard.class);

    private BackendTypeGuard() {}

    /**
     * A lifecycle hook whose whole job is the missing-adapter warning above.
     * Modules that own one local backend contribute this instead of restating
     * the anonymous RuntimeHook skeleton.
     */
    public static <T> RuntimeHook warnHook(
        Class<T> type,
        String typeKey,
        String subsystem
    ) {
        return new RuntimeHook() {
            @Override
            public void start(Container container) {
                warnIfExternal(container, type, typeKey, subsystem);
            }
        };
    }

    /**
     * Warns when {@code typeKey} resolves to a non-blank value other than
     * {@code local} AND the {@code @Local} binding is still the active one —
     * i.e. the user asked for an ext backend that is not installed. When a
     * extension adapter is bound primary, the configured type is honored
     * and no warning is emitted. Silent when the value is blank or
     * {@code local}.
     */
    public static <T> void warnIfExternal(
        Container container,
        Class<T> type,
        String typeKey,
        String subsystem
    ) {
        String configured = container.get(SymbolSource.class).resolve(typeKey, "");
        if (configured == null || configured.isBlank()
                || "local".equalsIgnoreCase(configured.trim())) {
            return;
        }
        if (container.isActiveBindingMarked(type, Local.class)) {
            LOG.warn(
                "freeway.cloud.{} type '{}' requires an extension adapter bound primary;"
                    + " none is installed, using the built-in local implementation"
                    + " and ignoring the setting",
                subsystem,
                configured.trim());
        }
    }
}
