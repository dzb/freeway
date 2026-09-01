package com.jujin.freeway.cloud.secret;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.BackendTypeGuard;
import com.jujin.freeway.cloud.internal.CloudConfigSymbolProvider;
import com.jujin.freeway.cloud.internal.SecretStoreDefault;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IoC wiring for the secret subsystem: {@link SecretStore} →
 * {@link SecretStoreDefault} (env/file, no {@code asMap()}, no fallback —
 * API-level security boundary), plus the {@link SecretSymbolSource} dynamic
 * provider so {@code @Symbol("db.password")} resolves secrets.
 *
 * <p>Installed BEFORE the config module (umbrella order): the secret provider
 * takes priority over the config provider in symbol resolution. The order is
 * enforced at startup — an inverted manual installation fails fast instead of
 * silently flipping the precedence.
 */
@Marker(Builtin.class)
public final class CloudSecretModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(SecretStore.class)
            .to((Container container) -> new SecretStoreDefault(secretFile()))
            .marker(Local.class)
            ;

        b.contribute(SymbolProvider.class).add(SecretSymbolSource.class);

        b.contribute(RuntimeHook.class)
            .add(CloudHooks.SECRET, new RuntimeHook() {
                @Override
                public void start(Container container) {
                    // In the hook, not the provider: resolving SymbolSource in
                    // the provider would instantiate SecretSymbolSource (which
                    // depends on SecretStore) while SecretStore is mid-construction.
                    verifyInstallOrder(container);
                    BackendTypeGuard.warnIfExternal(
                        container.get(SymbolSource.class), CloudConfigKeys.SECRET_TYPE, "secret");
                }
            });
    }

    /**
     * Fail-fast guard for the documented install order: the secret provider
     * must be contributed before the config provider, because symbol
     * resolution is first-match-wins in contribution order (see
     * {@code SymbolSourceDefault.raw()}) — a misordered assembly would
     * silently let config values outrank secrets. The umbrella
     * {@code CloudModule} installs correctly; this turns a misordered manual
     * installation into a startup error instead of a silent precedence flip.
     */
    private static void verifyInstallOrder(Container container) {
        List<String> providerIds = new ArrayList<>(
            container.extension(SymbolProvider.class).asMap().keySet());
        int secret = providerIds.indexOf(providerId(SecretSymbolSource.class));
        int config = providerIds.indexOf(providerId(CloudConfigSymbolProvider.class));
        if (secret >= 0 && config >= 0 && secret > config) {
            throw new IllegalStateException(
                "CloudSecretModule must be installed before CloudConfigModule: "
                    + "the secret symbol provider takes priority over the config provider "
                    + "in symbol resolution (first-contributed wins)");
        }
    }

    /** Canonical class-contribution id — mirrors {@code BinderImpl.add(Class)}. */
    private static String providerId(Class<?> implClass) {
        return Strings.camelToSnake(implClass.getSimpleName())
            + "@" + implClass.getPackageName();
    }

    /**
     * Secrets file path from {@code freeway.cloud.secret.file} (system
     * property) or the default — never through {@code SymbolSource}: the
     * secret provider participates in symbol resolution, so routing the path
     * through it would recurse (same rule as the config module).
     */
    private static Path secretFile() {
        String path = System.getProperty(CloudConfigKeys.SECRET_FILE);
        return Path.of(path == null ? "application-secrets.properties" : path);
    }
}
