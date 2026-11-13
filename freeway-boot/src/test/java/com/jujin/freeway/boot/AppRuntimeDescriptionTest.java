package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppConfigDefault;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppRuntimeDescriptionTest {

    static final class AppModule implements ModuleEx {
        @Override public void bind(Binder binder) {}
    }

    @Test
    void describesStateProfilesKeysAndCompositionWithoutValues() {
        AppConfig config = AppConfigDefault.of(
            Map.of("app.name", "demo"), List.of("dev"));

        try (AppRuntime app = FreewayApp.create(AppModule.class)
                .name("orders")
                .config(config)
                .autoDiscovery(false)
                .shutdownHook(false)
                .start()) {
            AppRuntimeDescription description = app.describe();

            assertEquals(AppState.RUNNING, description.state());
            assertEquals(List.of("dev"), description.profiles());
            assertTrue(description.configKeys().contains("app.name"));
            assertEquals("orders", description.container().application());
            assertTrue(description.container().modules().stream()
                .anyMatch(module -> module.type().equals(AppModule.class.getName())));
        }
    }
}
