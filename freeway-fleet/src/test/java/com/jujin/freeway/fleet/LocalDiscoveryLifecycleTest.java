package com.jujin.freeway.fleet;

import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.registration.RegistrationModule;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import com.jujin.freeway.cloud.registration.RegistrationModule.ConfigKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry lifecycle: built-in HTTP declaration auto-registers after the
 * server starts and unregisters on shutdown; host override for 0.0.0.0 / Pod
 * IP; without the HTTP module the HTTP declaration resolves to null and is
 * skipped instead of failing startup.
 */
class LocalDiscoveryLifecycleTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(ConfigKeys.REGISTRY_SERVICE_ID);
        System.clearProperty(ConfigKeys.REGISTRY_SERVICE_HOST);
        System.clearProperty(ConfigKeys.REGISTRY_SERVICE_PORT);
        System.clearProperty(ConfigKeys.REGISTRY_SERVICE_INSTANCE_ID);
    }

    @Test
    void autoRegistersHttpEndpointAndDeregistersOnStop() {
        System.setProperty(ConfigKeys.REGISTRY_SERVICE_ID, "lifecycle-svc");
        // Bind the store directly so the post-close assertion can inspect the
        // table after the container (and its proxy) is gone.
        InMemoryLeaseStore store = new InMemoryLeaseStore();
        try (AppRuntime app = FreewayApp.create(new HttpModule())
                .add(CloudModule.class)
                .add(new RegistrationModule())
                .add(new LocalDiscoveryModule())
                .add(binder -> binder.bind(LeaseStore.class)
                    .to(container -> store).primary())
                .start()) {
            List<ServiceInstance> instances = store.liveReadyInstances("lifecycle-svc", Duration.ofMinutes(1));
            assertEquals(1, instances.size(), "HTTP endpoint must auto-register");
            assertEquals(app.get(HttpServer.class).port(), instances.get(0).endpoint().port());
        }
        assertTrue(store.liveReadyInstances("lifecycle-svc", Duration.ofMinutes(1)).isEmpty(),
            "shutdown unregisters before the container closes");
    }

    @Test
    void serviceHostOverrideWinsOverBoundAddress() {
        System.setProperty(ConfigKeys.REGISTRY_SERVICE_ID, "host-svc");
        System.setProperty(ConfigKeys.REGISTRY_SERVICE_HOST, "myhost.example");
        try (AppRuntime app = FreewayApp.create(new HttpModule()).add(CloudModule.class).add(new RegistrationModule()).add(new LocalDiscoveryModule()).start()) {
            LeaseStore store = app.get(LeaseStore.class);
            List<ServiceInstance> instances = store.liveReadyInstances("host-svc", Duration.ofMinutes(1));
            assertEquals("myhost.example", instances.get(0).endpoint().host());
        }
    }

    @Test
    void customServiceDeclarationIsCollected() {
        try (AppRuntime app = FreewayApp.create(new AdminDeclarationModule(), new HttpModule()).add(CloudModule.class).add(new RegistrationModule()).add(new LocalDiscoveryModule()).start()) {
            LeaseStore store = app.get(LeaseStore.class);
            List<ServiceInstance> instances = store.liveReadyInstances("admin-svc", Duration.ofMinutes(1));
            assertEquals(1, instances.size());
            assertEquals(9091, instances.get(0).endpoint().port());
        }
    }

    @Test
    void registryHookWithoutHttpServerSkipsHttpDeclaration() {
        // HttpModule is normally SPI-discovered (freeway-http registers
        // META-INF/services). With auto-discovery off and no explicit HTTP
        // module, the registry hook's conditional ordering reference is
        // vacuous — startup succeeds and the HTTP declaration resolves to
        // null (nothing to register) instead of failing.
        try (AppRuntime app = FreewayApp.create(CloudModule.class, RegistrationModule.class, LocalDiscoveryModule.class).autoDiscovery(false).start()) {
            LeaseStore store = app.get(LeaseStore.class);
            assertTrue(store.liveReadyInstances("lifecycle-svc", Duration.ofMinutes(1)).isEmpty(),
                "without an HTTP server there is no endpoint to register");
        }
    }

    /** A module contributing its own endpoint declaration. */
    static class AdminDeclarationModule implements com.jujin.freeway.ioc.ModuleEx {
        @Override
        public void bind(com.jujin.freeway.ioc.Binder b) {
            b.contribute(com.jujin.freeway.cloud.discovery.ServiceDeclaration.class)
                .add("admin", (com.jujin.freeway.cloud.discovery.ServiceDeclaration)
                    container -> com.jujin.freeway.cloud.discovery.ServiceInstance.of(
                    "admin-svc", "admin-1",
                    com.jujin.freeway.cloud.discovery.ServiceEndpoint.of("http", "0.0.0.0", 9091)));
        }
    }
}
