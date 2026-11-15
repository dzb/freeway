package com.jujin.freeway.cloud.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A backend without readiness must be registered ready, not given an inert gate. */
class ReadinessCapabilityTest {

    private static final AtomicReference<ServiceHealth> HEALTH = new AtomicReference<>();

    static final class NoReadinessRegistry implements ServiceRegistry {
        @Override public void register(ServiceInstance instance) { }

        @Override public void register(ServiceInstance instance, ServiceHealth health) {
            HEALTH.set(health);
        }

        @Override public boolean renew(String serviceId, String instanceId) { return true; }

        @Override public void unregister(ServiceInstance instance) { }
    }

    static final class Backend implements ModuleEx {
        @Override public void bind(Binder binder) {
            binder.bind(ServiceRegistry.class).to(c -> new NoReadinessRegistry()).primary();
        }
    }

    @Test
    void aBackendWithoutReadinessIsRegisteredReady() {
        HEALTH.set(null);
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        try (AppRuntime ignored = FreewayApp.create(
                new HttpModule(), new RegistrationModule(), new Backend()).start()) {
            ServiceHealth health = HEALTH.get();
            assertEquals(true, health != null && health.live() && health.ready(),
                "no readiness capability must mean the instance is registered up");
        } finally {
            System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        }
    }
}
