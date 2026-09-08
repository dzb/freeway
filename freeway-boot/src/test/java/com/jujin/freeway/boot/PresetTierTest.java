package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppConfigDefault;
import com.jujin.freeway.boot.internal.AppConfigModule;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The preset tier: the lowest-precedence {@code SymbolProvider} — it fills
 * only what no higher source set, and the selector key itself is
 * bootstrap-only ({@code -Dfreeway.preset}), which is why it can also serve
 * keys outside the container's interest (e.g. the JUL log cascade's keys).
 */
class PresetTierTest {

    private static final String HOST = "freeway.http.server.host";

    @AfterEach
    void tearDown() {
        System.clearProperty(Presets.KEY);
        System.clearProperty(HOST);
    }

    private static String resolveHost(AppConfigDefault config) {
        try (Container container = Freeway.create(new AppConfigModule(config))) {
            return container.get(SymbolSource.class).resolve(HOST);
        } finally {
            config.close();
        }
    }

    private static AppConfigDefault plainConfig() {
        return new AppConfigDefault(Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    @Test
    void presetFillsWhatNothingHigherSet() {
        System.setProperty(Presets.KEY, "docker");
        assertEquals("0.0.0.0", resolveHost(plainConfig()),
            "the docker preset binds all interfaces by default");
    }

    @Test
    void systemPropertyOutranksThePreset() {
        System.setProperty(Presets.KEY, "docker");
        System.setProperty(HOST, "127.0.0.1");
        assertEquals("127.0.0.1", resolveHost(plainConfig()));
    }

    @Test
    void fileTierOutranksThePreset() {
        System.setProperty(Presets.KEY, "docker");
        assertEquals("192.168.1.10",
            resolveHost(new AppConfigDefault(
                Map.of(), Map.of(),
                Map.of(HOST, "192.168.1.10"), // baseline = the files tier
                List.of(), List.of())),
            "an explicit file value must win over the preset bundle");
    }

    @Test
    void noPresetLeavesTheChainUntouched() {
        try (Container container = Freeway.create(new AppConfigModule(plainConfig()))) {
            assertNull(container.get(SymbolSource.class).resolve(HOST, null),
                "without a preset the tier must not invent values");
        }
    }

    @Test
    void unknownPresetNameFailsAtConstruction() {
        System.setProperty(Presets.KEY, "kubernates");
        assertThrows(IllegalArgumentException.class, PresetTierTest::plainConfig);
    }
}
