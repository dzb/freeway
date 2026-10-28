package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.RegistryServerModule;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The startup readiness gate over the real HTTP surface: an instance is
 * registered immediately (liveness, heartbeats) but stays out of discovery
 * while a contributed readiness check is failing — the "registered = ready"
 * lie the seam's javadoc forbids — and appears once the check passes.
 *
 * <p>A failing-then-passing contributor is the slow-start shape: a pool still
 * warming, a migration still running. Nothing about the instance's identity
 * changes when it becomes ready; only the readiness bit does.</p>
 */
class ReadinessGateTest {

    private AppRuntime app;

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void cleanup() {
        if (app != null) {
            app.close();
        }
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
    }

    /** A dependency that answers "not yet" until {@code warm} flips. */
    private static ModuleEx slowDependency(AtomicBoolean warm) {
        return binder -> binder.contribute(CloudHealthContributor.class)
            .add("slow", new CloudHealthContributor() {
                @Override
                public String name() {
                    return "slow";
                }

                @Override
                public HealthResult check() {
                    return warm.get() ? HealthResult.ok() : HealthResult.unhealthy("warming up");
                }
            });
    }

    private ServiceInstance self() {
        return app.get(ServiceDiscovery.class).instances("freeway-app").stream().findFirst()
            .orElseThrow(() -> new AssertionError("the instance must be registered"));
    }

    @Test
    void a_warming_instance_is_registered_but_not_discoverable() throws Exception {
        AtomicBoolean warm = new AtomicBoolean(false);
        app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule(),
                slowDependency(warm))
            .start();

        assertEquals(0, app.get(ServiceDiscovery.class).instances("freeway-app").size(),
            "a warming instance must not be discoverable — that is the whole point");

        warm.set(true);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && app.get(ServiceDiscovery.class).instances("freeway-app").isEmpty()) {
            Thread.sleep(50);
        }
        assertTrue(app.get(ServiceDiscovery.class).instances("freeway-app").size() == 1,
            "the gate must publish the instance once the check passes");
    }

    @Test
    void a_fast_starting_app_is_ready_immediately() throws Exception {
        AtomicBoolean warm = new AtomicBoolean(true);
        app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule(),
                slowDependency(warm))
            .start();

        assertEquals(1, app.get(ServiceDiscovery.class).instances("freeway-app").size(),
            "a healthy dependency must not make the gate delay the instance");
        assertTrue(self().instanceId().startsWith("freeway-app"),
            "and it is the node's own instance");
    }
}
