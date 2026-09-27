package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.commons.logging.LogConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import java.util.Objects;
import java.util.Set;
import com.jujin.freeway.commons.util.EnvKeys;

/**
 * Wires the loaded {@link AppConfig} into the container: the config itself plus
 * the symbol sources it declares. That is the whole job — the runtime's other
 * collaborators belong to the runtime, not to the container's service set.
 */
@Marker(Builtin.class)
public final class BootModule implements ModuleEx {
    private final AppConfig config;

    public BootModule(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void bind(Binder binder) {
        binder.bind(AppConfig.class).to(container -> config);
        // The config declares its own symbol sources with their orders —
        // precedence comes from the declaration, never from module install
        // order, and a hot-reloading config's sources read live snapshots.
        for (SymbolProvider provider : config.providers()) {
            binder.contribute(SymbolProvider.class).add(provider);
        }
        // Boot's own vocabulary, harvested from its table like every other module's.
        binder.contribute(KnownKeys.class).add(
            KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
        // Logging is the only configuration the infrastructure modules own, and commons owns it:
        // its namespace and key names come from LogConfig, declared here because commons has no
        // binder of its own. The fixed keys give typo fixes; the per-file keys
        // (freeway.log.file.<name>.*) are dynamic and cannot be listed — their prefix is
        // admitted below, exempting the family from the unknown-key namespace rule.
        binder.contribute(KnownKeys.class).add(
            new KnownKeys(Set.of(LogConfig.PREFIX), LogConfig.knownKeys()));
        binder.contribute(KnownKeys.class).add(KnownKeys.admit(LogConfig.FILE_PREFIX));
        binder.contribute(RuntimeHook.class).add(UnknownKeysHook.HOOK_ID, new UnknownKeysHook());
    }


    /**
     * {@code boot}'s keys: the settings that configure the configuration system
     * itself — profile activation, the extra config file, the env-mapping prefix.
     *
     * <p>Not a feature module's surface. {@code boot}, {@code ioc} and {@code commons}
     * are the infrastructure modules — base capabilities only, and the one
     * configuration among them is logging's ({@code commons.logging.LogKeys}). These
     * three keys are the cascade's own knobs, read by {@link ConfigLoaderImpl}, and
     * they sit directly under the root namespace: {@link #PREFIX} is that root, and a
     * feature module's {@code freeway.<module>.*} table never holds one of them. The
     * infra tables are named {@code *Keys} ({@code LogKeys}, {@code EnvKeys},
     * {@code ConfigKeys}); the feature tables {@code *ConfigKeys}.
     */
    public static final class ConfigKeys {

        /** The namespace this module's keys live under: the framework root itself, so there is no
         *  module prefix to compose from — the keys below are spelled in full. */
        public static final String PREFIX = KnownKeys.ROOT_PREFIX;

        /** Profile activation ({@code -Dfreeway.profile}, {@code FREEWAY_PROFILE}, …). */
        public static final String PROFILE = "freeway.profile";

        /** The extra config file ({@code -D} or {@code FREEWAY_CONFIG_FILE}). */
        public static final String CONFIG_FILE = "freeway.config.file";

        /** The env-mapping prefix. The key is boot's (it reads it as a bootstrap
         *  setting); the mapping mechanism and its default live in {@link EnvKeys},
         *  which is where this spelling comes from. */
        public static final String ENV_PREFIX = EnvKeys.PREFIX_KEY;

        private ConfigKeys() {}
    }
}
