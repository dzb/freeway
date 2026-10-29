package com.jujin.freeway.cloud.registry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.RegistryServerModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.event.CloudEventModule;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole freeway-native discovery story on real processes: one registry
 * node, two services that have never heard of each other, no static peers —
 * they register through the wire, discover each other through the wire, the
 * feeder hands the answers to the mesh, and the mesh connects. When this test
 * is green, the freeway-native backend stands in for Consul end to end.
 */
class FreewayRegistryEndToEndTest {

    private AppRuntime node;
    private AppRuntime clientA;
    private AppRuntime clientB;

    @BeforeEach
    void ephemeralPorts() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void stopApps() {
        // Clients first: their stop deregisters through the wire, so the node must
        // still be listening. A startup failure leaves the field null — skip it.
        for (AppRuntime app : new AppRuntime[] {clientA, clientB, node}) {
            if (app != null) {
                app.close();
            }
        }
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
        System.clearProperty(CloudModule.ConfigKeys.REGISTRY_NODES);
        System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
        System.clearProperty("freeway.cloud.event.enabled");
        System.clearProperty("freeway.cloud.event.discovery-feed-ms");
        System.clearProperty("freeway.cloud.event.backoff-base-ms");
        System.clearProperty("freeway.cloud.event.backoff-max-ms");
        System.clearProperty("freeway.cloud.event.token");
    }

    private static int port(AppRuntime app) {
        return app.get(HttpServer.class).port();
    }

    @Test
    void two_clients_find_each_other_through_a_registry_node_and_mesh_up() throws Exception {
        // The node: holds the table, requires the shared secret.
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN, "s3cret");
        node = FreewayApp.run(
            new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule());
        int nodePort = port(node);

        // The clients: write through the wire, read through the wire, feed the mesh
        // from what the wire answers.
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_NODES, "127.0.0.1:" + nodePort);
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN, "s3cret");
        System.setProperty("freeway.cloud.event.enabled", "true");
        System.setProperty("freeway.cloud.event.discovery-feed-ms", "100");
        System.setProperty("freeway.cloud.event.backoff-base-ms", "100");
        System.setProperty("freeway.cloud.event.backoff-max-ms", "300");
        ModuleEx[] clientModules = new ModuleEx[] {
            new HttpModule(), new CloudDiscoveryModule(), new CloudEventModule(),
            new RegistryFleetModule()};
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        clientA = FreewayApp.run(clientModules);
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        clientB = FreewayApp.run(clientModules);

        // The node's table holds both clients — writes crossed the wire. (The node
        // itself is registered too, so the table has three entries; the two client
        // endpoints are what this assertion is about.)
        String serviceId = clientA.get(com.jujin.freeway.cloud.event.PeerHub.class).serviceId();
        int portA = port(clientA);
        int portB = port(clientB);
        assertTrue(await(() -> {
            List<ServiceInstance> onNode = instancesOnNode(node, serviceId);
            return onNode.stream().anyMatch(i -> i.endpoint().port() == portA)
                && onNode.stream().anyMatch(i -> i.endpoint().port() == portB);
        }, 8000), "both clients must be registered on the node: " + instancesOnNode(node, serviceId));

        // Each client discovers both through the wire (itself included here; the
        // feeder excludes it on the mesh side).
        assertTrue(await(
            () -> clientA.get(ServiceDiscovery.class).instances("freeway-app").size() >= 2,
            8000), "client A must discover both through the wire");
        assertTrue(await(
            () -> clientB.get(ServiceDiscovery.class).instances("freeway-app").size() >= 2,
            8000), "client B must discover both through the wire");

        // And the mesh self-configures: each client dials the peer it discovered.
        assertTrue(await(() -> meshed(clientA) && meshed(clientB), 15000),
            "the mesh must form purely from what discovery answered: A="
                + clientA.get(com.jujin.freeway.cloud.event.PeerHub.class).connections()
                + " B=" + clientB.get(com.jujin.freeway.cloud.event.PeerHub.class).connections());
    }

    // A "wrong token" client cannot be expressed here: the credential is one
    // key read by both sides (registry.token), so a node and a client in the
    // same JVM necessarily share its value. The refusal itself is pinned where
    // it lives — RegistryServerModuleTest (wrong/missing header → 401) and
    // RegistryNodeClientTest (a refused registration fails the startup loudly).

    private static boolean meshed(AppRuntime app) {
        return !app.get(com.jujin.freeway.cloud.event.PeerHub.class).connections().isEmpty();
    }

    private static List<ServiceInstance> instancesOnNode(AppRuntime node, String serviceId) {
        // The node serves discovery through its own local store — it has no
        // event module, so the queried service id arrives as a parameter.
        return node.get(ServiceDiscovery.class).instances(serviceId);
    }

    private static String causeChain(Throwable failure) {
        StringBuilder seen = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            seen.append(t.getMessage()).append(" | ");
        }
        return seen.toString();
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMs)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
