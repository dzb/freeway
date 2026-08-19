package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.RegistryStore;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry lifecycle: built-in HTTP declaration auto-registers after the
 * server starts and deregisters on shutdown; host override for 0.0.0.0 / Pod
 * IP; startup fails fast when the HTTP module is missing.
 */
class ServiceLifecycleTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.REGISTRY_SERVICE_ID);
        System.clearProperty(CloudConfigKeys.REGISTRY_SERVICE_HOST);
        System.clearProperty(CloudConfigKeys.REGISTRY_SERVICE_PORT);
        System.clearProperty(CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID);
    }

    @Test
    void autoRegistersHttpEndpointAndDeregistersOnStop() {
        System.setProperty(CloudConfigKeys.REGISTRY_SERVICE_ID, "lifecycle-svc");
        RegistryStore store;
        try (AppRuntime app = FreewayApp.run(new HttpModule(), new CloudModule())) {
            store = app.get(RegistryStore.class);
            List<ServiceInstance> instances = store.liveReady("lifecycle-svc", Duration.ofMinutes(1));
            assertEquals(1, instances.size(), "HTTP endpoint must auto-register");
            assertEquals(app.get(WebServer.class).port(), instances.get(0).endpoint().port());
        }
        assertTrue(store.liveReady("lifecycle-svc", Duration.ofMinutes(1)).isEmpty(),
            "shutdown deregisters before the container closes");
    }

    @Test
    void serviceHostOverrideWinsOverBoundAddress() {
        System.setProperty(CloudConfigKeys.REGISTRY_SERVICE_ID, "host-svc");
        System.setProperty(CloudConfigKeys.REGISTRY_SERVICE_HOST, "myhost.example");
        try (AppRuntime app = FreewayApp.run(new HttpModule(), new CloudModule())) {
            RegistryStore store = app.get(RegistryStore.class);
            List<ServiceInstance> instances = store.liveReady("host-svc", Duration.ofMinutes(1));
            assertEquals("myhost.example", instances.get(0).endpoint().host());
        }
    }

    @Test
    void customServiceDeclarationIsCollected() {
        try (AppRuntime app = FreewayApp.run(
            new AdminDeclarationModule(), new HttpModule(), new CloudModule())) {
            RegistryStore store = app.get(RegistryStore.class);
            List<ServiceInstance> instances = store.liveReady("admin-svc", Duration.ofMinutes(1));
            assertEquals(1, instances.size());
            assertEquals(9091, instances.get(0).endpoint().port());
        }
    }

    @Test
    void registryHookWithoutHttpServerFailsStartupClearly() {
        // HttpModule is normally SPI-discovered (freeway-http registers
        // META-INF/services). With auto-discovery off and no explicit HTTP
        // module, the registry hook's after("freeway.http.server") ordering
        // reference is missing — startup must fail (validateOrdering), not
        // silently skip.
        assertThrows(IllegalStateException.class,
            () -> FreewayApp.of(new CloudModule()).autoDiscovery(false).start());
    }

    /** A module contributing its own endpoint declaration. */
    static class AdminDeclarationModule implements com.jujin.freeway.ioc.ModuleEx {
        @Override
        public void bind(com.jujin.freeway.ioc.Binder b) {
            b.contribute(com.jujin.freeway.cloud.discovery.ServiceDeclaration.class)
                .add("admin", container -> com.jujin.freeway.cloud.discovery.ServiceInstance.of(
                    "admin-svc", "admin-1",
                    com.jujin.freeway.cloud.discovery.Endpoint.of("http", "0.0.0.0", 9091)));
        }
    }
}
