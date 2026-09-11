package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppConfigDefault;
import com.jujin.freeway.boot.internal.BootModule;
import com.jujin.freeway.boot.internal.ConfigSources;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The boot cascade contributes one {@code SymbolProvider} per tier with a
 * declared order: CLI arguments outrank environment variables, which outrank
 * the file tier — regardless of contribution order (the declared orders are
 * what the resolution actually uses). One tier holds one value per key, so the
 * chain, not the provider list, is what applies the precedence.
 */
class BootConfigTierTest {

    private static final String KEY = "db.password";

    private static AppConfig layered(String cliValue, String envValue, String fileValue) {
        return new AppConfigDefault(
            new ConfigSources(
                cliValue == null ? Map.of() : Map.of(KEY, cliValue),
                envValue == null ? Map.of() : Map.of(KEY, envValue),
                fileValue == null ? Map.of() : Map.of(KEY, fileValue),
                Map.of(),
                List.of()),
            List.of());
    }

    private static String resolve(AppConfig config) {
        try (Container container = Freeway.create(new BootModule(config))) {
            return container.get(SymbolSource.class).resolve(KEY);
        } finally {
            config.close();
        }
    }

    @Test
    void cliOutranksEnvAndFiles() {
        assertEquals("from-cli", resolve(layered("from-cli", "from-env", "from-file")));
    }

    @Test
    void envOutranksFilesWhenCliIsSilent() {
        assertEquals("from-env", resolve(layered(null, "from-env", "from-file")));
    }

    @Test
    void filesResolveWhenHigherTiersAreSilent() {
        assertEquals("from-file", resolve(layered(null, null, "from-file")));
    }
}
