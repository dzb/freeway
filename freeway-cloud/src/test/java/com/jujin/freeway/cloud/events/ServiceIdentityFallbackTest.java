package com.jujin.freeway.cloud.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.RegistryStore;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P1-3 regression (service-id default chain): the registry identity resolved
 * by {@code HttpServiceDeclaration} and the events-mesh origin resolved by
 * {@code CloudEventLifecycleHook} must walk the same fallback chain —
 * {@code freeway.cloud.registry.service-id} → {@code freeway.app.name} →
 * {@code "freeway-app"} — so a node configured only with
 * {@code freeway.app.name} registers and broadcasts under the SAME name.
 */
class ServiceIdentityFallbackTest {

    /** App-level key (boot config domain), used verbatim by HttpServiceDeclaration. */
    private static final String APP_NAME_KEY = "freeway.app.name";

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.REGISTRY_SERVICE_ID);
        System.clearProperty(APP_NAME_KEY);
        System.clearProperty(CloudConfigKeys.EVENTS_ENABLED);
    }

    @Test
    void appNameOnlyKeepsRegistrationAndMeshIdentitiesInLockstep() {
        System.setProperty(APP_NAME_KEY, "myapp");
        try (AppRuntime app = appWithEvents()) {
            assertEquals("myapp", app.get(PeerHub.class).serviceId(),
                "mesh origin must fall back to freeway.app.name");
            assertEquals("myapp", registeredServiceId(app, "myapp"),
                "registry identity must fall back to freeway.app.name");
        }
    }

    @Test
    void registryServiceIdOverridesAppNameForBothIdentities() {
        System.setProperty(APP_NAME_KEY, "myapp");
        System.setProperty(CloudConfigKeys.REGISTRY_SERVICE_ID, "custom");
        try (AppRuntime app = appWithEvents()) {
            assertEquals("custom", app.get(PeerHub.class).serviceId(),
                "explicit registry service-id must win for the mesh origin");
            assertEquals("custom", registeredServiceId(app, "custom"));
        }
    }

    @Test
    void unconfiguredIdentitiesShareTheFreewayAppDefault() {
        try (AppRuntime app = appWithEvents()) {
            assertEquals("freeway-app", app.get(PeerHub.class).serviceId());
            assertEquals("freeway-app", registeredServiceId(app, "freeway-app"));
        }
    }

    /** Events enabled so the lifecycle hook wires the hub with the mesh origin. */
    private static AppRuntime appWithEvents() {
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        return FreewayApp.run(
            new HttpModule(), new CloudModule(), new CloudEventModule());
    }

    /** Reads the identity HttpServiceDeclaration registered for this app. */
    private static String registeredServiceId(AppRuntime app, String serviceId) {
        RegistryStore store = app.get(RegistryStore.class);
        List<ServiceInstance> instances = store.liveReady(serviceId, Duration.ofMinutes(1));
        assertFalse(instances.isEmpty(),
            "no instance registered under '" + serviceId + "' — registration identity diverged");
        assertEquals(1, instances.size(), "one HTTP declaration registers one instance");
        return instances.get(0).serviceId();
    }
}
