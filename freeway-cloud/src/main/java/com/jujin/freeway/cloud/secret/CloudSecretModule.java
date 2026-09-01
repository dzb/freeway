package com.jujin.freeway.cloud.secret;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.BackendTypeGuard;
import com.jujin.freeway.cloud.internal.SecretStoreDefault;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Path;

/**
 * IoC wiring for the secret subsystem: {@link SecretStore} →
 * {@link SecretStoreDefault} (env/file, no {@code asMap()}, no fallback —
 * API-level security boundary), plus the {@link SecretSymbolSource} dynamic
 * provider so {@code @Symbol("db.password")} resolves secrets.
 *
 * <p>Secret precedence is declared, not positional: {@code SecretSymbolSource}
 * declares {@code order()} 15 — between the framework's env tier (10) and
 * file tier (20) — so secrets win over every file-based source regardless of
 * install order.
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
                    BackendTypeGuard.warnIfExternal(
                        container.get(SymbolSource.class), CloudConfigKeys.SECRET_TYPE, "secret");
                }
            });
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
