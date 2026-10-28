package com.jujin.freeway.cloud.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Binding the module selects the backend: the two discovery interfaces resolve
 * to the fleet client, and the fleet must be named — a backend bound with no
 * nodes is a configuration that can never work, and fails startup saying so.
 */
class RegistryFleetModuleTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(CloudModule.ConfigKeys.REGISTRY_NODES);
        System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
    }

    @Test
    void binding_the_module_selects_the_fleet_as_the_backend() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        // No CloudDiscoveryModule here: its lifecycle hook would register through
        // the fleet at startup, and no node is listening in this test. The
        // assertion is about the bindings, not the lifecycle.
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_NODES, "127.0.0.1:1");
        try (AppRuntime app = FreewayApp.create(new RegistryFleetModule()).start()) {
            // The container proxies the service behind its interface, so the shape
            // is asserted through the resolved fleet, and the behavior (an
            // unreachable fleet fails the registration loudly) is the next test.
            assertEquals(1, app.get(RegistryNodes.class).addresses().size(),
                "the configured fleet is what the backend was built with");
            assertTrue(app.get(ServiceRegistry.class) != null);
            assertTrue(app.get(ServiceDiscovery.class) != null);
        }
    }

    @Test
    void the_module_without_nodes_fails_startup_naming_the_key() {
        // CloudDiscoveryModule contributes the lifecycle hook that registers at
        // startup — the thing that actually resolves the node list.
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> FreewayApp.create(new RegistryFleetModule(), new CloudDiscoveryModule()).start());
        assertTrue(causeChain(failure).contains(CloudModule.ConfigKeys.REGISTRY_NODES),
            "the failure must name the key: " + causeChain(failure));
    }

    /** An app that registers through the fleet reaches every node — the same
     *  lifecycle the Consul adapter rides, driven by the core hook. */
    @Test
    void a_bound_app_registers_through_the_fleet() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_NODES, "127.0.0.1:1"); // unreachable: writes fail loudly at startup
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> FreewayApp.create(
                new RegistryFleetModule(), new CloudModule(), new HttpModule()).start());
        assertTrue(causeChain(failure).contains("registration")
                || causeChain(failure).contains("registry node"),
            "an unreachable fleet must fail the registration loudly: " + causeChain(failure));
    }

    private static String causeChain(Throwable failure) {
        StringBuilder seen = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            seen.append(t.getMessage()).append(" | ");
        }
        return seen.toString();
    }
}
