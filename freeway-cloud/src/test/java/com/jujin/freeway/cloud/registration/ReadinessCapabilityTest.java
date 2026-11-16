package com.jujin.freeway.cloud.registration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A backend without the readiness capability is registered through the base API. */
class ReadinessCapabilityTest {

    private static final AtomicReference<ServiceInstance> REGISTERED = new AtomicReference<>();

    static final class NoReadinessBackend implements ServiceRegistry {
        @Override public void register(ServiceInstance instance) {
            REGISTERED.set(instance);
        }

        @Override public boolean renew(String serviceId, String instanceId) { return true; }

        @Override public void unregister(ServiceInstance instance) { }
    }

    static final class Backend implements ModuleEx {
        @Override public void bind(Binder binder) {
            binder.bind(ServiceRegistry.class).to(c -> new NoReadinessBackend()).primary();
        }
    }

    @Test
    void aBackendWithoutReadinessIsRegisteredWithoutAGate() {
        REGISTERED.set(null);
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        try (AppRuntime ignored = FreewayApp.create(
                new HttpModule(), new RegistrationModule(), new Backend()).start()) {
            assertNotNull(REGISTERED.get(),
                "a backend without readiness must be registered through register(instance)");
        } finally {
            System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        }
    }
}
