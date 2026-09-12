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
 * The boot-side {@code LogConfigSource}: only {@code freeway.log.*} keys of the
 * application file baseline are exposed, and the values come from the same
 * file-family read the main cascade uses.
 */
class AppLogSourceTest {

    private static Map<String, String> values() {
        return new AppLogSource().values();
    }

    @Test
    void exposesTheLogSubsetOfTheApplicationFiles() {
        Map<String, String> values = valuesWithAppFile(
            "freeway.log.file=auto\napp.name=Freeway Boot\n");

        assertEquals("auto", values.get("freeway.log.file"),
            "the app file's log key flows through");
        assertNull(values.get("app.name"),
            "non-log keys must never surface as phantom logger names");
    }

    @Test
    void emptyWithoutAnySource() {
        assertTrue(values().isEmpty(),
            "no log keys in the test classpath — nothing to expose");
    }

    /** {@link #values()} with the classpath {@code application.properties}
     *  replaced by {@code content} (the loader resolves through the TCCL). */
    private static Map<String, String> valuesWithAppFile(String content) {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(saved) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("application.properties".equals(name)) {
                    return new ByteArrayInputStream(
                        content.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }
        };
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return values();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }
}
