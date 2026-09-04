package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    void asyncCallPreservesInvocationContext() throws Exception {
        try (AppRuntime app = FreewayApp.run(
                new HeaderEchoModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("echo", "i1",
                    Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);

            InvocationContext ctx = InvocationContext.of(
                null, null, Baggage.of(Map.of("k", "v")));
            CloudResponse resp = InvocationContext.runWith(ctx, () ->
                client.callAsync("echo", CloudRequest.get("/api/header")).join());
            assertTrue(resp.is2xx());
            assertEquals("k=v", resp.bodyAsString(),
                "async RPC must propagate the caller's invocation context");
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
    void unmappedLocalFailureSurfacesAsCloudException() {
        // A discovery backend bug must not leak a raw RuntimeException: the
        // caller sees CloudException (non-retryable, cause attached), and a
        // half-open probe still gets an outcome instead of being lost.
        ServiceDiscovery broken = serviceId -> {
            throw new IllegalStateException("registry backend exploded");
        };
        LoadBalancer balancer = instances -> Optional.empty();
        CloudHttpClientDefault client = new CloudHttpClientDefault(broken, balancer);
        try {
            CloudException ex = assertThrows(CloudException.class,
                () -> client.call("svc", CloudRequest.get("/x")));
            assertFalse(ex.retryable(), "a dispatch failure is deterministic — never retried");
            assertEquals(-1, ex.status());
            assertEquals("registry backend exploded", ex.getCause().getMessage());
        } finally {
            client.close();
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

    @Test
    void halfOpenProbeRecoversWhenServiceComesBack() throws Exception {
        // Full breaker lifecycle over the wire: failures open the circuit,
        // the OPEN window elapses, the next call is the half-open probe, and
        // a recovered service closes the circuit again.
        System.setProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "0");
        System.setProperty(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD, "2");
        System.setProperty(CloudConfigKeys.RPC_CB_OPEN_WINDOW, "1");
        try (AppRuntime app = FreewayApp.run(new FlippingModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("flip", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);
            FlippingModule.fail.set(true);

            assertThrows(CloudException.class,
                () -> client.call("flip", CloudRequest.get("/api/flip"))); // failure 1
            assertThrows(CloudException.class,
                () -> client.call("flip", CloudRequest.get("/api/flip"))); // failure 2 -> OPEN
            CloudException opened = assertThrows(CloudException.class,
                () -> client.call("flip", CloudRequest.get("/api/flip")));
            assertFalse(opened.retryable(), "circuit open rejects without hitting the wire");
            assertTrue(opened.getMessage().contains("Circuit"));

            // The service recovers while the circuit is OPEN.
            FlippingModule.fail.set(false);

            Thread.sleep(1200); // let the 1s open window elapse

            CloudResponse probe = client.call("flip", CloudRequest.get("/api/flip"));
            assertTrue(probe.is2xx(),
                "the half-open probe must reach the recovered service and close the circuit");
            assertTrue(client.call("flip", CloudRequest.get("/api/flip")).is2xx(),
                "after recovery the circuit stays closed and serves normally");
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            System.clearProperty(CloudConfigKeys.RPC_CB_OPEN_WINDOW);
            FlippingModule.fail.set(true);
        }
    }

    @Test
    void interruptedCallIsNotRetried() throws Exception {
        try (AppRuntime app = FreewayApp.run(new SlowModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("slow", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptFlagKept = new AtomicBoolean();
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    client.call("slow", CloudRequest.get("/api/slow"));
                } catch (CloudException ex) {
                    failure.set(ex);
                    interruptFlagKept.set(Thread.currentThread().isInterrupted());
                }
            });
            Thread.sleep(300); // let the call block mid-send
            caller.interrupt();
            caller.join(5000);

            assertTrue(failure.get() instanceof CloudException, "got: " + failure.get());
            CloudException ex = (CloudException) failure.get();
            assertFalse(ex.retryable(),
                "an interrupted call must surface immediately — the caller asked to stop");
            assertTrue(ex.getMessage().contains("interrupt"));
            assertTrue(interruptFlagKept.get(), "interrupt flag preserved for the caller");
        }
    }

    @Test
    void circuitOpenOnOneServiceDoesNotPoisonAnother() {
        // Breakers are sharded per serviceId even when assembled through the
        // standard injection path (CloudResilienceModule binds one breaker):
        // exhausting the failure window on "failing" must leave "healthy"
        // fully servable.
        System.setProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "0");
        System.setProperty(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD, "2");
        System.setProperty(CloudConfigKeys.RPC_CB_OPEN_WINDOW, "60");
        try (AppRuntime app = FreewayApp.run(new TwoRouteModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            ServiceRegistry registry = app.get(ServiceRegistry.class);
            registry.register(
                ServiceInstance.of("failing", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            registry.register(
                ServiceInstance.of("healthy", "i2", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);

            assertThrows(CloudException.class, () -> client.call("failing", CloudRequest.get("/api/fail")));
            assertThrows(CloudException.class, () -> client.call("failing", CloudRequest.get("/api/fail")));
            CloudException opened = assertThrows(CloudException.class,
                () -> client.call("failing", CloudRequest.get("/api/fail")));
            assertFalse(opened.retryable());
            assertTrue(opened.getMessage().contains("Circuit"), "circuit on 'failing' is open");

            assertTrue(client.call("healthy", CloudRequest.get("/api/echo")).is2xx(),
                "a healthy service must not be rejected by another service's open circuit");
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            System.clearProperty(CloudConfigKeys.RPC_CB_OPEN_WINDOW);
        }
    }

    @Test
    void rateLimitIsPerService() {
        System.setProperty(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, "true");
        System.setProperty(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND, "1");
        try (AppRuntime app = FreewayApp.run(new TwoRouteModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            ServiceRegistry registry = app.get(ServiceRegistry.class);
            registry.register(
                ServiceInstance.of("svc-a", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));
            registry.register(
                ServiceInstance.of("svc-b", "i2", Endpoint.of("http", server.host(), server.port()), Map.of()));
            CloudHttpClient client = app.get(CloudHttpClient.class);

            assertTrue(client.call("svc-a", CloudRequest.get("/api/echo")).is2xx(),
                "svc-a consumes its own burst budget");
            assertTrue(client.call("svc-b", CloudRequest.get("/api/echo")).is2xx(),
                "svc-b has its own limiter shard and is not throttled by svc-a");
        } finally {
            System.clearProperty(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED);
            System.clearProperty(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND);
        }
    }

    /** One application exposing both an echo and an always-failing route. */
    static class TwoRouteModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/echo", ctx -> ctx.send(200, "{\"ok\":true}")))
                .add(Route.get("/api/fail", ctx -> ctx.send(500, "boom")));
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

    static class HeaderEchoModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/header", ctx ->
                    ctx.send(200, ctx.header("baggage").orElse(""))));
        }
    }

    /** Flips between HTTP 500 and 200 under test control. */
    static class FlippingModule implements ModuleEx {
        static final java.util.concurrent.atomic.AtomicBoolean fail =
            new java.util.concurrent.atomic.AtomicBoolean(true);

        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/flip", ctx -> {
                    if (fail.get()) {
                        ctx.send(500, "boom");
                    } else {
                        ctx.send(200, "{\"ok\":true}");
                    }
                }));
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
