package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudModule;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
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
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: RpcHttpClient calls a plain Freeway HTTP application registered
 * in the in-process registry. The callee carries zero cloud code.
 */
class RpcHttpClientTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(ConfigKeys.RPC_REQUEST_TIMEOUT);
    }

    @Test
    void callsRegisteredHttpService() {
        try (AppRuntime app = FreewayApp.create(new EchoModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("echo", "i1",
                    ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));

            RpcResponse resp = app.get(RpcHttpClient.class)
                .call("echo", RpcRequest.get("/api/echo"));
            assertTrue(resp.isSuccess());
            assertEquals("{\"ok\":true}", resp.bodyAsString());
        }
    }

    @Test
    void asyncCallPreservesInvocationContext() throws Exception {
        try (AppRuntime app = FreewayApp.create(new HeaderEchoModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("echo", "i1",
                    ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            InvocationContext ctx = InvocationContext.of(
                null, null, Baggage.of(Map.of("k", "v")));
            RpcResponse resp = InvocationContext.runWith(ctx, () ->
                client.callAsync("echo", RpcRequest.get("/api/header")).join());
            assertTrue(resp.isSuccess());
            assertEquals("k=v", resp.bodyAsString(),
                "async RPC must propagate the caller's invocation context");
        }
    }

    @Test
    void unknownServiceThrowsNoInstance() {
        try (AppRuntime app = FreewayApp.create(new HttpModule()).add(CloudModule.class).start()) {
            RpcHttpClient client = app.get(RpcHttpClient.class);
            RpcException ex = assertThrows(RpcException.class,
                () -> client.call("missing", RpcRequest.get("/x")));
            assertFalse(ex.retryable(), "no instance is a configuration error, not a retryable failure");
            assertEquals(-1, ex.status());
        }
    }

    @Test
    void unmappedLocalFailureSurfacesAsRpcException() {
        // A discovery backend bug must not leak a raw RuntimeException: the
        // caller sees RpcException (non-retryable, cause attached), and a
        // half-open probe still gets an outcome instead of being lost.
        ServiceDiscovery broken = serviceId -> {
            throw new IllegalStateException("registry backend exploded");
        };
        LoadBalancer balancer = instances -> Optional.empty();
        RpcHttpClientDefault client = new RpcHttpClientDefault(broken, balancer);
        try {
            RpcException ex = assertThrows(RpcException.class,
                () -> client.call("svc", RpcRequest.get("/x")));
            assertFalse(ex.retryable(), "a dispatch failure is deterministic — never retried");
            assertEquals(-1, ex.status());
            assertEquals("registry backend exploded", ex.getCause().getMessage());
        } finally {
            client.close();
        }
    }

    @Test
    void unreachableInstanceThrowsRetryableConnectFailure() {
        try (AppRuntime app = FreewayApp.create(new HttpModule()).add(CloudModule.class).start()) {
            // Nothing listens on port 1 on localhost.
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("dead", "i1", ServiceEndpoint.of("http", "127.0.0.1", 1), Map.of()));
            RpcException ex = assertThrows(RpcException.class,
                () -> app.get(RpcHttpClient.class).call("dead", RpcRequest.get("/x")));
            assertTrue(ex.retryable(), "connect failure is retryable");
        }
    }

    @Test
    void slowEndpointTimesOut() {
        System.setProperty(ConfigKeys.RPC_REQUEST_TIMEOUT, "200");
        try (AppRuntime app = FreewayApp.create(new SlowModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("slow", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcException ex = assertThrows(RpcException.class,
                () -> app.get(RpcHttpClient.class).call("slow", RpcRequest.get("/api/slow")));
            assertTrue(ex.retryable(), "request timeout is retryable");
        } finally {
            System.clearProperty(ConfigKeys.RPC_REQUEST_TIMEOUT);
        }
    }

    @Test
    void retriesWithANewInstanceWhenTheFirstIsDown() {
        System.setProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "2");
        System.setProperty(ConfigKeys.RPC_RETRY_BACKOFF_BASE, "10");
        try (AppRuntime app = FreewayApp.create(new EchoModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            ServiceRegistry registry = app.get(ServiceRegistry.class);
            registry.register(ServiceInstance.of("retry-svc", "dead", ServiceEndpoint.of("http", "127.0.0.1", 1), Map.of()));
            registry.register(ServiceInstance.of("retry-svc", "alive",
                ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));

            RpcResponse resp = app.get(RpcHttpClient.class)
                .call("retry-svc", RpcRequest.get("/api/echo"));
            assertTrue(resp.isSuccess(), "a retry must re-choose the live instance");
        } finally {
            System.clearProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(ConfigKeys.RPC_RETRY_BACKOFF_BASE);
        }
    }

    @Test
    void serverErrorsRetriedAndOpenTheCircuit() {
        System.setProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "0");
        System.setProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD, "2");
        System.setProperty(ConfigKeys.RPC_CB_OPEN_WINDOW, "60");
        try (AppRuntime app = FreewayApp.create(new FailModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("failing", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            RpcException first = assertThrows(RpcException.class,
                () -> client.call("failing", RpcRequest.get("/api/fail")));
            assertTrue(first.retryable(), "5xx is retryable");
            assertEquals(500, first.status());

            assertThrows(RpcException.class, () -> client.call("failing", RpcRequest.get("/api/fail")));
            RpcException opened = assertThrows(RpcException.class,
                () -> client.call("failing", RpcRequest.get("/api/fail")));
            assertFalse(opened.retryable(), "circuit open is a local rejection");
            assertTrue(opened.getMessage().contains("Circuit"));
        } finally {
            System.clearProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            System.clearProperty(ConfigKeys.RPC_CB_OPEN_WINDOW);
        }
    }

    @Test
    void serverErrorIsNotReplayedForNonIdempotentRequests() {
        System.setProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "2");
        System.setProperty(ConfigKeys.RPC_RETRY_BACKOFF_BASE, "10");
        try (AppRuntime app = FreewayApp.create(new CountingFailModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("failing", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            RpcException ex = assertThrows(RpcException.class, () ->
                client.call("failing", RpcRequest.post("/api/fail", new byte[0], "application/json")));
            assertTrue(ex.retryable(), "5xx is still a retryable class of failure");
            assertTrue(ex.outcomeUnknown(), "the peer may have applied the POST before answering 5xx");
            assertEquals(1, CountingFailModule.posts.get(),
                "an ambiguous outcome must not be replayed for a POST");
        } finally {
            System.clearProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(ConfigKeys.RPC_RETRY_BACKOFF_BASE);
            CountingFailModule.reset();
        }
    }

    @Test
    void markedIdempotentRequestsReplayAmbiguousOutcomes() {
        System.setProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "2");
        System.setProperty(ConfigKeys.RPC_RETRY_BACKOFF_BASE, "10");
        // The retries below must not trip the default breaker (threshold 5)
        // mid-test — this test pins retry counts, not breaker accounting.
        System.setProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD, "50");
        try (AppRuntime app = FreewayApp.create(new CountingFailModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("failing", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            RpcRequest marked = RpcRequest
                .post("/api/fail", new byte[0], "application/json")
                .withIdempotent(true);
            assertThrows(RpcException.class, () -> client.call("failing", marked));
            assertEquals(3, CountingFailModule.posts.get(),
                "an explicitly idempotent POST is replayed: initial + two retries");

            assertThrows(RpcException.class, () -> client.call("failing", RpcRequest.get("/api/fail")));
            assertEquals(3, CountingFailModule.gets.get(),
                "GET derives to idempotent and is replayed without a marker");
        } finally {
            System.clearProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(ConfigKeys.RPC_RETRY_BACKOFF_BASE);
            System.clearProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            CountingFailModule.reset();
        }
    }

    @Test
    void rateLimiterRejectsExcessCalls() {
        System.setProperty(ConfigKeys.RPC_RATE_LIMIT_ENABLED, "true");
        System.setProperty(ConfigKeys.RPC_RATE_LIMIT_PER_SECOND, "1");
        try (AppRuntime app = FreewayApp.create(new EchoModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("limited", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            assertTrue(client.call("limited", RpcRequest.get("/api/echo")).isSuccess());
            RpcException ex = assertThrows(RpcException.class,
                () -> client.call("limited", RpcRequest.get("/api/echo")));
            assertFalse(ex.retryable(), "rate limited is a local rejection");
            assertTrue(ex.getMessage().contains("Rate limit"));
        } finally {
            System.clearProperty(ConfigKeys.RPC_RATE_LIMIT_ENABLED);
            System.clearProperty(ConfigKeys.RPC_RATE_LIMIT_PER_SECOND);
        }
    }

    @Test
    void halfOpenProbeRecoversWhenServiceComesBack() throws Exception {
        // Full breaker lifecycle over the wire: failures open the circuit,
        // the OPEN window elapses, the next call is the half-open probe, and
        // a recovered service closes the circuit again.
        System.setProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "0");
        System.setProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD, "2");
        System.setProperty(ConfigKeys.RPC_CB_OPEN_WINDOW, "1");
        try (AppRuntime app = FreewayApp.create(new FlippingModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("flip", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);
            FlippingModule.fail.set(true);

            assertThrows(RpcException.class,
                () -> client.call("flip", RpcRequest.get("/api/flip"))); // failure 1
            assertThrows(RpcException.class,
                () -> client.call("flip", RpcRequest.get("/api/flip"))); // failure 2 -> OPEN
            RpcException opened = assertThrows(RpcException.class,
                () -> client.call("flip", RpcRequest.get("/api/flip")));
            assertFalse(opened.retryable(), "circuit open rejects without hitting the wire");
            assertTrue(opened.getMessage().contains("Circuit"));

            // The service recovers while the circuit is OPEN.
            FlippingModule.fail.set(false);

            Thread.sleep(1200); // let the 1s open window elapse

            RpcResponse probe = client.call("flip", RpcRequest.get("/api/flip"));
            assertTrue(probe.isSuccess(),
                "the half-open probe must reach the recovered service and close the circuit");
            assertTrue(client.call("flip", RpcRequest.get("/api/flip")).isSuccess(),
                "after recovery the circuit stays closed and serves normally");
        } finally {
            System.clearProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            System.clearProperty(ConfigKeys.RPC_CB_OPEN_WINDOW);
            FlippingModule.fail.set(true);
        }
    }

    @Test
    void interruptedCallIsNotRetried() throws Exception {
        try (AppRuntime app = FreewayApp.create(new SlowModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("slow", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptFlagKept = new AtomicBoolean();
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    client.call("slow", RpcRequest.get("/api/slow"));
                } catch (RpcException ex) {
                    failure.set(ex);
                    interruptFlagKept.set(Thread.currentThread().isInterrupted());
                }
            });
            Thread.sleep(300); // let the call block mid-send
            caller.interrupt();
            caller.join(5000);

            assertTrue(failure.get() instanceof RpcException, "got: " + failure.get());
            RpcException ex = (RpcException) failure.get();
            assertFalse(ex.retryable(),
                "an interrupted call must surface immediately — the caller asked to stop");
            assertTrue(ex.getMessage().contains("interrupt"));
            assertTrue(interruptFlagKept.get(), "interrupt flag preserved for the caller");
        }
    }

    @Test
    void circuitOpenOnOneServiceDoesNotPoisonAnother() {
        // Breakers are sharded per serviceId even when assembled through the
        // standard injection path (ResilienceModule binds one breaker):
        // exhausting the failure window on "failing" must leave "healthy"
        // fully servable.
        System.setProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "0");
        System.setProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD, "2");
        System.setProperty(ConfigKeys.RPC_CB_OPEN_WINDOW, "60");
        try (AppRuntime app = FreewayApp.create(new TwoRouteModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            ServiceRegistry registry = app.get(ServiceRegistry.class);
            registry.register(
                ServiceInstance.of("failing", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            registry.register(
                ServiceInstance.of("healthy", "i2", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            assertThrows(RpcException.class, () -> client.call("failing", RpcRequest.get("/api/fail")));
            assertThrows(RpcException.class, () -> client.call("failing", RpcRequest.get("/api/fail")));
            RpcException opened = assertThrows(RpcException.class,
                () -> client.call("failing", RpcRequest.get("/api/fail")));
            assertFalse(opened.retryable());
            assertTrue(opened.getMessage().contains("Circuit"), "circuit on 'failing' is open");

            assertTrue(client.call("healthy", RpcRequest.get("/api/echo")).isSuccess(),
                "a healthy service must not be rejected by another service's open circuit");
        } finally {
            System.clearProperty(ConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
            System.clearProperty(ConfigKeys.RPC_CB_FAILURE_THRESHOLD);
            System.clearProperty(ConfigKeys.RPC_CB_OPEN_WINDOW);
        }
    }

    @Test
    void rateLimitIsPerService() {
        System.setProperty(ConfigKeys.RPC_RATE_LIMIT_ENABLED, "true");
        System.setProperty(ConfigKeys.RPC_RATE_LIMIT_PER_SECOND, "1");
        try (AppRuntime app = FreewayApp.create(new TwoRouteModule(), new HttpModule()).add(CloudModule.class).start()) {
            HttpServer server = app.get(HttpServer.class);
            ServiceRegistry registry = app.get(ServiceRegistry.class);
            registry.register(
                ServiceInstance.of("svc-a", "i1", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            registry.register(
                ServiceInstance.of("svc-b", "i2", ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
            RpcHttpClient client = app.get(RpcHttpClient.class);

            assertTrue(client.call("svc-a", RpcRequest.get("/api/echo")).isSuccess(),
                "svc-a consumes its own burst budget");
            assertTrue(client.call("svc-b", RpcRequest.get("/api/echo")).isSuccess(),
                "svc-b has its own limiter shard and is not throttled by svc-a");
        } finally {
            System.clearProperty(ConfigKeys.RPC_RATE_LIMIT_ENABLED);
            System.clearProperty(ConfigKeys.RPC_RATE_LIMIT_PER_SECOND);
        }
    }

    /** One application exposing both an echo and an always-failing route. */
    static class TwoRouteModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/echo", ctx -> ctx.sendText(200, "{\"ok\":true}")))
                .add(Route.get("/api/fail", ctx -> ctx.sendText(500, "boom")));
        }
    }

    /** A plain Freeway HTTP application — no cloud code. */
    static class EchoModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/echo", ctx -> ctx.sendText(200, "{\"ok\":true}")));
        }
    }

    static class HeaderEchoModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/header", ctx ->
                    ctx.sendText(200, ctx.header("baggage").orElse(""))));
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
                        ctx.sendText(500, "boom");
                    } else {
                        ctx.sendText(200, "{\"ok\":true}");
                    }
                }));
        }
    }

    /** Always fails with HTTP 500, counting hits per verb so tests can pin
     *  exactly how often the ambiguous outcome was replayed. */
    static class CountingFailModule implements ModuleEx {
        static final java.util.concurrent.atomic.AtomicInteger posts =
            new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.atomic.AtomicInteger gets =
            new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.post("/api/fail", ctx -> {
                    posts.incrementAndGet();
                    ctx.sendText(500, "boom");
                }))
                .add(Route.get("/api/fail", ctx -> {
                    gets.incrementAndGet();
                    ctx.sendText(500, "boom");
                }));
        }

        static void reset() {
            posts.set(0);
            gets.set(0);
        }
    }

    /** Always fails with HTTP 500. */
    static class FailModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(Route.class)
                .add(Route.get("/api/fail", ctx -> ctx.sendText(500, "boom")));
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
                    ctx.sendText(200, "late");
                }));
        }
    }
}
