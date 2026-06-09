package com.jujin.freeway.boot.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootConfigLoaderTest {
    @Test
    void keepsSourcesSeparateAndAppliesPrecedenceOnMerge() {
        BootConfigLoader.BootConfigLayers layers = BootConfigLoader.loadLayers(
            Thread.currentThread().getContextClassLoader(),
            "--freeway.profile=dev",
            "--app.name=Overridden",
            "--server.port=7070"
        );

        assertEquals(List.of("dev"), layers.profiles());
        assertEquals("Freeway Boot", layers.properties().get("app.name"));
        assertEquals("9090", layers.properties().get("server.port"));
        assertEquals("Standalone IoC container", layers.json().get("app.description"));
        assertEquals("1.0.0", layers.json().get("app.version"));
        assertEquals("localhost", layers.json().get("server.host"));
        assertEquals("Dev Boot", layers.profileProperties().get("app.name"));
        assertEquals("9191", layers.profileProperties().get("server.port"));
        assertEquals("Profiled IoC container", layers.profileJson().get("app.description"));
        assertEquals("dev.localhost", layers.profileJson().get("server.host"));
        assertEquals("Overridden", layers.args().get("app.name"));
        assertEquals("7070", layers.args().get("server.port"));
        assertEquals("Overridden", layers.merged().get("app.name"));
        assertEquals("7070", layers.merged().get("server.port"));
        assertEquals("Profiled IoC container", layers.merged().get("app.description"));
        assertEquals("dev.localhost", layers.merged().get("server.host"));
    }

    @Test
    void rejectsOversizedPropertiesResource() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            BootConfigLoader.loadLayers(new OversizedPropertiesLoader()));

        assertTrue(ex.getMessage().contains("Unable to load application.properties"));
        assertTrue(ex.getCause().getMessage().contains("exceeds"));
    }

    @Test
    void rejectsProfileNamesThatCanAddressOtherResources() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            BootConfigLoader.loadLayers(BootConfigLoaderTest.class.getClassLoader(), "--freeway.profile=../secret"));

        assertTrue(ex.getMessage().contains("Invalid freeway.profile value"));
    }

    private static final class OversizedPropertiesLoader extends ClassLoader {
        @Override
        public InputStream getResourceAsStream(String name) {
            if ("application.properties".equals(name)) {
                return new RepeatingInputStream(16L * 1024 * 1024 + 1);
            }
            return null;
        }
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return 'a';
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int read = (int) Math.min(len, remaining);
            java.util.Arrays.fill(bytes, off, off + read, (byte) 'a');
            remaining -= read;
            return read;
        }
    }
}
