package com.jujin.freeway.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void valueFollowsTheDeclaredPreset() {
        assertNull(Presets.value("freeway.log.file"));
        System.setProperty(Presets.KEY, "docker");
        assertEquals("off", Presets.value("freeway.log.file"));
        assertEquals("0.0.0.0", Presets.value("freeway.http.server.host"));
        assertNull(Presets.value("freeway.db.url"),
            "only the bundle's keys are served");
    }

    @Test
    void unknownNameFailsNamingTheChoices() {
        assertThrows(IllegalArgumentException.class, () -> Presets.validate("kubernates"));
        assertTrue(Presets.names().contains("docker"));
        assertTrue(Presets.names().contains("local"));
    }
}
