package com.jujin.freeway.cloud.secret;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.SecretStoreDefault;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.nio.file.Path;

/**
 * IoC wiring for the secret subsystem: {@link SecretStore} →
 * {@link SecretStoreDefault} (env/file, no {@code asMap()}, no fallback —
 * API-level security boundary), plus the {@link SecretSymbolSource} dynamic
 * provider so {@code @Symbol("db.password")} resolves secrets.
 *
 * <p>Installed BEFORE the config module (umbrella order): the secret provider
 * takes priority over the config provider in symbol resolution.
 */
@Marker(Builtin.class)
public final class CloudSecretModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(SecretStore.class)
            .to((com.jujin.freeway.ioc.Container container) -> new SecretStoreDefault(secretFile()))
            .marker(Local.class)
            ;

        b.contribute(SymbolProvider.class).add(SecretSymbolSource.class);
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
