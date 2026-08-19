package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.rpc.CloudException;
import com.jujin.freeway.cloud.rpc.CloudHttpClient;
import com.jujin.freeway.cloud.rpc.CloudRequest;
import com.jujin.freeway.cloud.rpc.CloudResponse;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: CloudHttpClient calls a plain Freeway HTTP application registered
 * in the in-process registry. The callee carries zero cloud code.
 */
class CloudHttpClientTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.RPC_REQUEST_TIMEOUT);
    }

    @Test
    void callsRegisteredHttpService() {
        try (AppRuntime app = FreewayApp.run(
            new EchoModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("echo", "i1",
                    Endpoint.of("http", server.host(), server.port()), Map.of()));

            CloudResponse resp = app.get(CloudHttpClient.class)
                .call("echo", CloudRequest.get("/api/echo"));
            assertTrue(resp.is2xx());
            assertEquals("{\"ok\":true}", resp.bodyAsString());
        }
    }

    @Test
    void unknownServiceThrowsNoInstance() {
        try (AppRuntime app = FreewayApp.run(new HttpModule(), new CloudModule())) {
            CloudHttpClient client = app.get(CloudHttpClient.class);
            CloudException ex = assertThrows(CloudException.class,
                () -> client.call("missing", CloudRequest.get("/x")));
            assertFalse(ex.retryable(), "no instance is a configuration error, not a retryable failure");
            assertEquals(-1, ex.status());
        }
    }

    @Test
    void unreachableInstanceThrowsRetryableConnectFailure() {
        try (AppRuntime app = FreewayApp.run(new HttpModule(), new CloudModule())) {
            // Nothing listens on port 1 on localhost.
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("dead", "i1", Endpoint.of("http", "127.0.0.1", 1), Map.of()));
            CloudException ex = assertThrows(CloudException.class,
                () -> app.get(CloudHttpClient.class).call("dead", CloudRequest.get("/x")));
            assertTrue(ex.retryable(), "connect failure is retryable");
        }
    }

    @Test
    void slowEndpointTimesOut() {
        System.setProperty(CloudConfigKeys.RPC_REQUEST_TIMEOUT, "200");
        try (AppRuntime app = FreewayApp.run(new SlowModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("slow", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudException ex = assertThrows(CloudException.class,
                () -> app.get(CloudHttpClient.class).call("slow", CloudRequest.get("/api/slow")));
            assertTrue(ex.retryable(), "request timeout is retryable");
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_REQUEST_TIMEOUT);
        }
    }

    @Test
    void retriesWithANewInstanceWhenTheFirstIsDown() {
        System.setProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "2");
        System.setProperty(CloudConfigKeys.RPC_RETRY_BACKOFF_BASE, "10");
        try (AppRuntime app = FreewayApp.run(new EchoModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            ServiceRegistry registry = app.get(ServiceRegistry.class);
            registry.register(ServiceInstance.of("retry-svc", "dead", Endpoint.of("http", "127.0.0.1", 1), Map.of()));
            registry.register(ServiceInstance.of("retry-svc", "alive",
                Endpoint.of("http", server.host(), server.port()), Map.of()));

            CloudResponse resp = app.get(CloudHttpClient.class)
                .call("retry-svc", CloudRequest.get("/api/echo"));
            assertTrue(resp.is2xx(), "a retry must re-choose the live instance");
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(CloudConfigKeys.RPC_RETRY_BACKOFF_BASE);
        }
    }

    @Test
    void serverErrorsRetriedAndOpenTheCircuit() {
        System.setProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "0");
        System.setProperty(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD, "2");
        System.setProperty(CloudConfigKeys.RPC_CB_OPEN_WINDOW, "60");
        try (AppRuntime app = FreewayApp.run(new FailModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("failing", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);

            CloudException first = assertThrows(CloudException.class,
                () -> client.call("failing", CloudRequest.get("/api/fail")));
            assertTrue(first.retryable(), "5xx is retryable");
            assertEquals(500, first.status());

            assertThrows(CloudException.class, () -> client.call("failing", CloudRequest.get("/api/fail")));
            CloudException opened = assertThrows(CloudException.class,
                () -> client.call("failing", CloudRequest.get("/api/fail")));
            assertFalse(opened.retryable(), "circuit open is a local rejection");
            assertTrue(opened.getMessage().contains("Circuit"));
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            System.clearProperty(CloudConfigKeys.RPC_CB_OPEN_WINDOW);
        }
    }

    @Test
    void rateLimiterRejectsExcessCalls() {
        System.setProperty(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, "true");
        System.setProperty(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND, "1");
        try (AppRuntime app = FreewayApp.run(new EchoModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("limited", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);

            assertTrue(client.call("limited", CloudRequest.get("/api/echo")).is2xx());
            CloudException ex = assertThrows(CloudException.class,
                () -> client.call("limited", CloudRequest.get("/api/echo")));
            assertFalse(ex.retryable(), "rate limited is a local rejection");
            assertTrue(ex.getMessage().contains("Rate limit"));
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED);
            System.clearProperty(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND);
        }
    }

    /** A plain Freeway HTTP application — no cloud code. */
    static class EchoModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/echo", ctx -> ctx.send(200, "{\"ok\":true}")));
        }
    }

    /** Always fails with HTTP 500. */
    static class FailModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/fail", ctx -> ctx.send(500, "boom")));
        }
    }

    /** Slow endpoint: sleeps well beyond the configured request timeout. */
    static class SlowModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/slow", ctx -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ctx.send(200, "late");
                }));
        }
    }
}
