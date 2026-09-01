package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfigModule;
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
 * what the resolution actually uses).
 */
class BootConfigTierTest {

    private static final String KEY = "db.password";

    private static AppConfig layered(String cliValue, String envValue, String fileValue) {
        return new AppConfigDefault(
            cliValue == null ? Map.of() : Map.of(KEY, cliValue),
            envValue == null ? Map.of() : Map.of(KEY, envValue),
            fileValue == null ? Map.of() : Map.of(KEY, fileValue), // baseline = the files tier
            List.of(), // no filesystem overrides
            List.of());
    }

    private static String resolve(AppConfig config) {
        try (Container container = Freeway.create(new BootConfigModule(config))) {
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
