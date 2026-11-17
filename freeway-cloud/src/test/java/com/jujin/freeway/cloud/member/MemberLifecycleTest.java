package com.jujin.freeway.cloud.member;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.TestDiscoveryModule;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registration lifecycle is backend-neutral: it registers through a
 *  fixture backend, without the removed local registry. */
class MemberLifecycleTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(MemberModule.ConfigKeys.MEMBER_SERVICE_ID);
        System.clearProperty(MemberModule.ConfigKeys.MEMBER_SERVICE_HOST);
        System.clearProperty(MemberModule.ConfigKeys.MEMBER_SERVICE_PORT);
    }

    @Test
    void autoRegistersTheHttpEndpointAndDeregistersOnStop() {
        System.setProperty(MemberModule.ConfigKeys.MEMBER_SERVICE_ID, "lifecycle-svc");
        TestDiscoveryModule fixture = new TestDiscoveryModule();
        int port;
        try (AppRuntime app = FreewayApp.create(new HttpModule(), new MemberModule(), fixture).start()) {
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
        try (AppRuntime app = FreewayApp.create(new MemberModule(), fixture)
                .autoDiscovery(false).start()) {
            assertTrue(fixture.backend().instances("freeway-app").isEmpty());
        }
    }
}
