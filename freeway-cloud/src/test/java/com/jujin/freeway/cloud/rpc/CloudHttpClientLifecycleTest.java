package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;

/**
 * Container shutdown must reach the client through {@code AutoCloseable}.
 *
 * <p>{@code CloudHttpClientDefault.close()} used to carry
 * {@code @PreDestroy} on top of implementing {@code AutoCloseable}. The
 * annotation is gone, and this pins what removing it was supposed to leave
 * untouched: closing the container closes the client, and a later call is
 * refused rather than attempted against a released pool.
 *
 * <p>Without this the removal would have been invisible. A class carrying both
 * callbacks reaches both of them ({@code Shutdown} keeps one identity set per
 * phase), so "still works" and "silently stopped being closed" look identical
 * to every other test in the module — this test is what tells them apart.
 */
class CloudHttpClientLifecycleTest {

    private static CloudHttpClientDefault client() {
        // No instances: the point is the client's own state, not the transport.
        return new CloudHttpClientDefault(
            serviceId -> List.<ServiceInstance>of(),
            new LoadBalancerDefault(),
            new CloudHttpClientDefault.Wiring(
                List.of(), null, null, null, TransportSecurity.NONE, null, null,
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ZERO));
    }

    @Test
    void closingTheContainerClosesTheClient() {
        try (Container container = Freeway.create(
                binder -> binder.bind(CloudHttpClientDefault.class).to(c -> client()))) {

            CloudHttpClientDefault client = container.get(CloudHttpClientDefault.class);

            container.close();

            // The refusal is what proves the release happened. A client that
            // was never closed would instead fail on the wire — with no
            // instances configured that surfaces as a CloudException, so the
            // type of failure distinguishes "closed" from "could not reach".
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.call("svc", CloudRequest.get("/x")),
                "closing the container must close the client");
            assertTrue(ex.getMessage().contains("closed"),
                "and the refusal must say so: " + ex.getMessage());
        }
    }

    @Test
    void cleanupHangsOffTheJdkInterfaceRatherThanTheAnnotation() {
        assertTrue(AutoCloseable.class.isAssignableFrom(CloudHttpClientDefault.class),
            "cleanup must hang off the JDK interface: the container sees it by "
                + "type, and a reader can check it by eye");

        boolean annotated = Arrays.stream(CloudHttpClientDefault.class.getDeclaredMethods())
            .anyMatch(m -> m.isAnnotationPresent(
                com.jujin.freeway.ioc.annotation.PreDestroy.class));
        assertFalse(annotated,
            "a @PreDestroy here would take the one cleanup phase meant for work "
                + "that must still publish on the EventBus, in exchange for "
                + "nothing — the interface already covers this case");
    }
}
