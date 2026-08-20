package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.config.CloudConfig;
import com.jujin.freeway.cloud.config.CloudConfigModule;
import com.jujin.freeway.cloud.config.ConfigChangedEvent;
import com.jujin.freeway.cloud.config.ConfigRef;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.EventSubscriber;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Config center wiring: dynamic SymbolProvider (hot @Value/@Symbol reads),
 * ConfigRef tracking and ConfigChangedEvent delivery on the EventBus.
 */
class CloudConfigModuleTest {
    @BeforeEach
    void randomPort() {
        System.setProperty("server.port", "0");
    }


    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(com.jujin.freeway.cloud.CloudConfigKeys.CONFIG_FILE);
    }

    @Test
    void symbolProviderReadsLatestConfigValue() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "greeting=hello\n");
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.CONFIG_FILE, file.toString());
        try (AppRuntime app = FreewayApp.run(new CloudConfigModule())) {
            SymbolSource symbols = app.get(SymbolSource.class);
            assertEquals("hello", symbols.resolve("greeting"));
            Files.writeString(file, "greeting=world\n");
            await(() -> "world".equals(symbols.resolve("greeting")));
        }
    }

    @Test
    void configRefTracksLatestValue() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "port=8080\n");
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.CONFIG_FILE, file.toString());
        try (AppRuntime app = FreewayApp.run(new CloudConfigModule())) {
            CloudConfig config = app.get(CloudConfig.class);
            ConfigRef<Integer> ref = ConfigRef.of(config, "port", int.class, 1);
            assertEquals(8080, ref.get());
            Files.writeString(file, "port=9090\n");
            await(() -> ref.get() == 9090);
        }
    }

    @Test
    void configChangedEventDeliveredOnEventBus() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "k=v1\n");
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.CONFIG_FILE, file.toString());
        try (AppRuntime app = FreewayApp.run(new ListenerModule(), new CloudConfigModule())) {
            app.get(CloudConfig.class); // force construction
            Files.writeString(file, "k=v2\n");
            await(() -> !ListenerModule.EVENTS.isEmpty());
            ConfigChangedEvent event = ListenerModule.EVENTS.get(0);
            assertEquals("k", event.key());
            assertEquals("v1", event.oldValue());
            assertEquals("v2", event.newValue());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("condition not met within timeout");
    }

    static class ListenerModule implements ModuleEx {
        static final List<ConfigChangedEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public void bind(Binder b) {
            b.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(ConfigChangedEvent.class, (java.util.function.Consumer<ConfigChangedEvent>) EVENTS::add));
        }
    }
}
