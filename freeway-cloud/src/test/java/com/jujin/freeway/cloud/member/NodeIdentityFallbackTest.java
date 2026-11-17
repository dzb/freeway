package com.jujin.freeway.cloud.member;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.TestDiscoveryModule;
import com.jujin.freeway.cloud.event.EventMeshModule;
import com.jujin.freeway.cloud.event.PeerHub;
import com.jujin.freeway.http.HttpModule;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P1-3 regression (service-id default chain): the registry identity resolved
 * by {@code HttpServiceDeclaration} and the event-mesh origin resolved by
 * {@code MeshEventLifecycleHook} must walk the same fallback chain —
 * {@code freeway.cloud.member.service-id} → {@code freeway.app.name} →
 * {@code "freeway-app"} — so a node configured only with
 * {@code freeway.app.name} registers and broadcasts under the SAME name.
 */
class NodeIdentityFallbackTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(MemberModule.ConfigKeys.MEMBER_SERVICE_ID);
        System.clearProperty(ConfigKeys.APP_NAME);
        System.clearProperty(com.jujin.freeway.cloud.event.EventMeshModule.ConfigKeys.EVENT_ENABLED);
    }

    @Test
    void appNameOnlyKeepsMemberAndMeshIdentitiesInLockstep() {
        System.setProperty(ConfigKeys.APP_NAME, "myapp");
        try (AppRuntime app = appWithEvents()) {
            assertEquals("myapp", app.get(PeerHub.class).serviceId(),
                "mesh origin must fall back to freeway.app.name");
            assertEquals("myapp", registeredServiceId(app, "myapp"),
                "registry identity must fall back to freeway.app.name");
        }
    }

    @Test
    void registryServiceIdOverridesAppNameForBothIdentities() {
        System.setProperty(ConfigKeys.APP_NAME, "myapp");
        System.setProperty(MemberModule.ConfigKeys.MEMBER_SERVICE_ID, "custom");
        try (AppRuntime app = appWithEvents()) {
            assertEquals("custom", app.get(PeerHub.class).serviceId(),
                "explicit member service-id must win for the mesh origin");
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
        System.setProperty(com.jujin.freeway.cloud.event.EventMeshModule.ConfigKeys.EVENT_ENABLED, "true");
        return FreewayApp.create(new HttpModule()).add(CloudModule.class).add(new MemberModule()).add(new TestDiscoveryModule()).add(new EventMeshModule()).start();
    }

    /** Reads the identity HttpServiceDeclaration registered for this app. */
    private static String registeredServiceId(AppRuntime app, String serviceId) {
        var backend = app.get(TestDiscoveryModule.TestBackend.class);
        List<ServiceInstance> instances = backend.instances(serviceId);
        assertFalse(instances.isEmpty(),
            "no instance registered under '" + serviceId + "' — registration identity diverged");
        assertEquals(1, instances.size(), "one HTTP declaration registers one instance");
        return instances.get(0).serviceId();
    }
}
