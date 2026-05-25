package com.jujin.freeway.boot.internal;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
