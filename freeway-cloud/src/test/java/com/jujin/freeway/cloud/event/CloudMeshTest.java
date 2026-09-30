package com.jujin.freeway.cloud.event;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.http.HttpModule;
import org.junit.jupiter.api.AfterEach;

/**
 * The two-node mesh rig both mesh test classes stand on: the pair of nodes
 * under test, and the teardown that puts the JVM back the way it found them.
 *
 * <p>The teardown is here rather than copied because it clears
 * <em>system properties</em>, and a property cleared in one class but not the
 * other leaks into every test that runs after it — a failure that shows up in
 * a class nobody was looking at. One place to add a property is one place that
 * cannot forget.
 */
abstract class CloudMeshTest {

    protected AppRuntime nodeA;
    protected AppRuntime nodeB;

    @AfterEach
    void cleanup() {
        if (nodeA != null) nodeA.close();
        if (nodeB != null) nodeB.close();
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(CloudModule.ConfigKeys.EVENT_PEERS);
        System.clearProperty(CloudModule.ConfigKeys.EVENT_ENABLED);
    }

    /**
     * Blocks until each node sees the other in its {@link PeerHub}. Mesh
     * activation is a dial plus a hello handshake, so a publish issued before
     * it would be published to nobody — the tests wait rather than sleep.
     */
    protected static void awaitMesh(AppRuntime a, AppRuntime b) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            boolean aSeesB = !a.get(PeerHub.class).connections().isEmpty();
            boolean bSeesA = !b.get(PeerHub.class).connections().isEmpty();
            if (aSeesB && bSeesA) return;
            Thread.sleep(50);
        }
        throw new AssertionError("mesh not established within 5s");
    }
}
