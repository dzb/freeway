package com.jujin.freeway.cloud.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.TestDiscoveryModule;
import com.jujin.freeway.http.HttpModule;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The registration lifecycle is backend-neutral: it registers through a
 *  fixture backend, without the removed local registry. */
class RegistrationLifecycleTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(RegistrationModule.ConfigKeys.REGISTRY_SERVICE_ID);
        System.clearProperty(RegistrationModule.ConfigKeys.REGISTRY_SERVICE_HOST);
        System.clearProperty(RegistrationModule.ConfigKeys.REGISTRY_SERVICE_PORT);
    }

    @Test
    void autoRegistersTheHttpEndpointAndDeregistersOnStop() {
        System.setProperty(RegistrationModule.ConfigKeys.REGISTRY_SERVICE_ID, "lifecycle-svc");
        TestDiscoveryModule fixture = new TestDiscoveryModule();
        int port;
        try (AppRuntime app = FreewayApp.create(new HttpModule(), new RegistrationModule(), fixture).start()) {
            port = app.get(com.jujin.freeway.http.HttpServer.class).port();
            List<com.jujin.freeway.cloud.discovery.ServiceInstance> instances =
                fixture.backend().instances("lifecycle-svc");
            assertEquals(1, instances.size(), "HTTP endpoint must auto-register");
            assertEquals(port, instances.get(0).endpoint().port());
        }
        assertTrue(fixture.backend().instances("lifecycle-svc").isEmpty(),
            "shutdown unregisters before the container closes");
    }

    @Test
    void withoutHttpModuleThereIsNothingToRegister() {
        TestDiscoveryModule fixture = new TestDiscoveryModule();
        try (AppRuntime app = FreewayApp.create(new RegistrationModule(), fixture)
                .autoDiscovery(false).start()) {
            assertTrue(fixture.backend().instances("freeway-app").isEmpty());
        }
    }
}
