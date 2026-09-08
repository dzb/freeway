package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.Presets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Bundle lookup, bootstrap-only declaration and startup validation. */
class PresetsTest {

    @org.junit.jupiter.api.AfterEach
    void clear() {
        System.clearProperty(Presets.KEY);
    }

    @Test
    void dockerBundleCarriesContainerDefaults() {
        Map<String, String> docker = Presets.bundle("docker");
        assertEquals("0.0.0.0", docker.get("freeway.http.server.host"));
        assertEquals("off", docker.get("freeway.log.file"));
        assertEquals(Map.of(), Presets.bundle("local"),
            "local states dev intent; the defaults already are dev-friendly");
        assertNull(Presets.bundle("nope"), "an unknown name must not produce values");
    }

    @Test
    void declaredReadsTheSystemPropertyThenEnv() {
        assertNull(Presets.declared());
        System.setProperty(Presets.KEY, "docker");
        assertEquals("docker", Presets.declared());
        System.setProperty(Presets.KEY, "  docker  ");
        assertEquals("docker", Presets.declared(), "the declared name is stripped");
        System.setProperty(Presets.KEY, "  ");
        assertNull(Presets.declared(), "a blank property is not a declaration");
    }

    @Test
    void bundleFollowsTheDeclaredPreset() {
        assertNull(Presets.bundle(Presets.declared()), "no declaration, no bundle");
        System.setProperty(Presets.KEY, "docker");
        Map<String, String> active = Presets.bundle(Presets.declared());
        assertEquals("off", active.get("freeway.log.file"));
        assertEquals("0.0.0.0", active.get("freeway.http.server.host"));
        assertNull(active.get("freeway.db.url"), "only the bundle's keys are served");
    }

    @Test
    void unknownNameFailsNamingTheChoices() {
        assertThrows(IllegalArgumentException.class, () -> Presets.validate("kubernates"));
        assertDoesNotThrow(() -> Presets.validate("docker"));
        assertDoesNotThrow(() -> Presets.validate("local"));
    }
}
