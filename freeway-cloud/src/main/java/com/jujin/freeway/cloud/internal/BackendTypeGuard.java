package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend-<code>type</code> config guard. The {@code freeway.cloud.*.type}
 * keys select an external backend (Nacos/Consul/K8s/S3/Vault/...) provided by
 * a backend bound primary — an extension adapter (Consul ships in freeway-ext)
 * or the freeway-native fleet ({@code FleetModule}). For roles that still have a
 * built-in default, a non-blank declaration is ignored while that built-in
 * binding is primary; {@link #warnIfExternal} reports that once. Discovery and
 * registration have no built-in default, so their guard asks whether any binding
 * exists instead ({@link #warnIfMissing}).
 */
public final class BackendTypeGuard {

    private static final Logger LOG = LoggerFactory.getLogger(BackendTypeGuard.class);

    private BackendTypeGuard() {}

    /**
     * A lifecycle hook whose whole job is the declaration warning above.
     * Modules that own one built-in backend contribute this instead of
     * restating the anonymous RuntimeHook skeleton.
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
     * Warns when a non-blank backend type is declared but the role has no
     * binding at all. This is the discovery/service-membership case: there is
     * no {@code @Local} marker to inspect, and a missing provider is the fact
     * that matters.
     *
     * @return true when a warning was emitted
     */
    public static <T> boolean warnIfMissing(
        Container container,
        Class<T> type,
        String typeKey,
        String subsystem
    ) {
        String configured = container.get(SymbolSource.class).resolve(typeKey, "");
        if (configured == null || configured.isBlank()) {
            return false;
        }
        try {
            container.get(type);
            return false;
        } catch (MissingBindingException absent) {
            LOG.warn(
                "freeway.cloud.{} type '{}' is configured but no implementation is bound;"
                    + " install an adapter or clear the key",
                subsystem,
                configured.trim());
            return true;
        }
    }

    /**
     * Warns when {@code typeKey} resolves to a non-blank value AND the
     * {@code @Local} binding is still the active one — i.e. an adapter backend
     * declaration has no adapter installed. When an extension adapter is bound
     * primary, the configured type is honored and no warning is emitted. Silent
     * when the value is blank.
     */
    public static <T> void warnIfExternal(
        Container container,
        Class<T> type,
        String typeKey,
        String subsystem
    ) {
        String configured = container.get(SymbolSource.class).resolve(typeKey, "");
        if (configured == null || configured.isBlank()) {
            return;
        }
        if (container.isActiveBindingMarked(type, Local.class)) {
            LOG.warn(
                "freeway.cloud.{} type '{}' is configured, but the built-in"
                    + " implementation is still primary; bind an adapter primary"
                    + " or clear the key — the configured type is ignored",
                subsystem,
                configured.trim());
        }
    }
}
