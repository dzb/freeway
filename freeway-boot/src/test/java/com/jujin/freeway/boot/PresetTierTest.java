package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppConfigDefault;
import com.jujin.freeway.boot.internal.BootModule;
import com.jujin.freeway.boot.internal.ConfigSources;
import com.jujin.freeway.boot.internal.Presets;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The preset tier: the lowest-precedence symbol source — it fills only what no
 * higher source set, so it can also serve keys outside the container's
 * interest (e.g. the JUL log cascade's keys). The bundle arrives as a source
 * like any other; resolving the bootstrap key is the loader's job
 * ({@code ConfigLoaderImplTest} covers the -D/env selection and validation).
 */
class PresetTierTest {

    private static final String HOST = "freeway.http.server.host";

    @AfterEach
    void tearDown() {
        System.clearProperty(Presets.KEY);
        System.clearProperty(HOST);
    }

    private static String resolveHost(AppConfigDefault config) {
        try (Container container = Freeway.create(new BootModule(config))) {
            return container.get(SymbolSource.class).resolve(HOST, null);
        } finally {
            config.close();
        }
    }

    /** The active preset exactly as the loader delivers it: one more source. */
    private static AppConfigDefault withDockerPreset(Map<String, String> files) {
        return new AppConfigDefault(
            new ConfigSources(
                Map.of(), Map.of(), files, Presets.bundle("docker"), List.of()),
            List.of());
    }

    @Test
    void presetFillsWhatNothingHigherSet() {
        assertEquals("0.0.0.0", resolveHost(withDockerPreset(Map.of())),
            "the docker preset binds all interfaces by default");
    }

    @Test
    void systemPropertyOutranksThePreset() {
        System.setProperty(HOST, "127.0.0.1");
        assertEquals("127.0.0.1", resolveHost(withDockerPreset(Map.of())));
    }

    @Test
    void fileTierOutranksThePreset() {
        assertEquals("192.168.1.10",
            resolveHost(withDockerPreset(Map.of(HOST, "192.168.1.10"))),
            "an explicit file value must win over the preset bundle");
    }

    @Test
    void emptyBundleLeavesTheChainUntouched() {
        // No preset declared: the loader contributes an empty source, which
        // must not invent values.
        AppConfigDefault config = new AppConfigDefault(
            new ConfigSources(Map.of(), Map.of(), Map.of(), Map.of(), List.of()),
            List.of());
        try (Container container = Freeway.create(new BootModule(config))) {
            assertNull(container.get(SymbolSource.class).resolve(HOST, null));
        } finally {
            config.close();
        }
    }
}
