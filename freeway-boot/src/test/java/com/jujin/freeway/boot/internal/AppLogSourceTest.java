package com.jujin.freeway.boot.internal;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot-side {@code LogConfigSource}: the application file baseline
 * outranks the preset, only {@code freeway.log.*} keys are exposed, and the
 * values come from the same file-family read the main cascade uses.
 */
class AppLogSourceTest {

    private static Map<String, String> values() {
        return new AppLogSource().values();
    }

    @Test
    void filesRankAboveThePreset() {
        System.setProperty(Presets.KEY, "docker"); // preset: freeway.log.file=off
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(saved) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("application.properties".equals(name)) {
                    return new ByteArrayInputStream(
                        "freeway.log.file=auto\n".getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }
        };
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Map<String, String> values = values();
            assertEquals("auto", values.get("freeway.log.file"),
                "an explicit file value wins over the preset");
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
            System.clearProperty(Presets.KEY);
        }
    }

    @Test
    void onlyLogKeysAreExposed() {
        System.setProperty(Presets.KEY, "docker");
        try {
            Map<String, String> values = values();
            assertEquals("off", values.get("freeway.log.file"),
                "the preset's log subset flows through when no file sets it");
            assertNull(values.get("freeway.http.server.host"),
                "the preset's non-log keys must never surface");
        } finally {
            System.clearProperty(Presets.KEY);
        }
    }

    @Test
    void emptyWithoutAnySource() {
        assertTrue(values().isEmpty(),
            "no declaration and no log keys in the test classpath — nothing to expose");
    }
}
