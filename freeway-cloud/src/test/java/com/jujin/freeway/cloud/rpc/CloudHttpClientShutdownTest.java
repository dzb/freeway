package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Shutdown of the outbound client: stop admitting, let what is in flight
 * finish, and only then fail what the grace could not drain. Cutting a call the
 * peer may already have applied is the failure mode this exists to avoid — so
 * the tests drive it against a real server whose handler blocks.
 */
class CloudHttpClientShutdownTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clear() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    @Test
    void closeWaitsForAnInFlightCallInsteadOfFailingIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AppRuntime server = server(new BlockingHandlers(started, release))) {
            CloudHttpClientDefault client =
                client(server, Duration.ofSeconds(5), Duration.ofSeconds(5));
            CompletableFuture<CloudResponse> inFlight =
                client.callAsync("svc", CloudRequest.get("/slow"), Duration.ofSeconds(10));
            assertTrue(started.await(5, TimeUnit.SECONDS), "the handler must be running");

            // close() runs while the call is still being served: it must wait.
            Thread closer = Thread.startVirtualThread(client::close);
            release.countDown();
            closer.join(10_000);

            assertFalse(closer.isAlive(), "close must return once the call drained");
            assertEquals("done", inFlight.join().bodyAsString(),
                "a call already accepted must finish, not be failed by shutdown");
        }
    }

    @Test
    void theGraceExpiresAndThenTheCallIsFailed() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AppRuntime server = server(new BlockingHandlers(started, release))) {
            // Short per-attempt timeout: the transport must not outlive the
            // test's grace by ten seconds of socket wait.
            CloudHttpClientDefault client =
                client(server, Duration.ofMillis(80), Duration.ofMillis(300));
            CompletableFuture<CloudResponse> stuck =
                client.callAsync("svc", CloudRequest.get("/slow"), Duration.ofSeconds(10));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            client.close();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            release.countDown(); // let the server handler finish and its thread exit
            assertTrue(elapsedMillis >= 60,
                "close must give the configured grace, elapsed=" + elapsedMillis);
            RuntimeException failure = assertThrows(RuntimeException.class, stuck::join);
            assertTrue(chainContains(failure, "CloudHttpClient is closed"),
                "what the grace could not drain fails with the closed message: " + failure);
        }
    }

    @Test
    void callsArrivingDuringCloseAreRefused() {
        CloudHttpClientDefault client = client(null, Duration.ofSeconds(1), Duration.ofSeconds(1));
        client.close();

        AtomicReference<Throwable> syncFailure = new AtomicReference<>();
        try {
            client.call("svc", CloudRequest.get("/x"));
        } catch (RuntimeException e) {
            syncFailure.set(e);
        }
        assertTrue(syncFailure.get() instanceof IllegalStateException,
            "a new synchronous call after close must be refused, got: " + syncFailure.get());

        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        try {
            client.callAsync("svc", CloudRequest.get("/x"), null);
        } catch (RuntimeException e) {
            asyncFailure.set(e);
        }
        assertTrue(asyncFailure.get() instanceof IllegalStateException,
            "a new async call after close must be refused, got: " + asyncFailure.get());
    }

    @Test
    void anIdleClientClosesWithoutWaitingForTheGrace() {
        CloudHttpClientDefault client = client(null, Duration.ofSeconds(30), Duration.ofSeconds(1));

        long start = System.nanoTime();
        client.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 5_000,
            "nothing in flight means nothing to wait for, elapsed=" + elapsedMillis);
    }

    /** A client pointed at the running server (or at nothing, for refusal tests). */
    private static CloudHttpClientDefault client(AppRuntime server, Duration grace,
                                                 Duration requestTimeout) {
        List<ServiceInstance> instances = server == null
            ? List.of()
            : List.of(ServiceInstance.of("svc", "i1",
                Endpoint.of("http", "127.0.0.1", server.get(WebServer.class).port())));
        return new CloudHttpClientDefault(
            serviceId -> instances,
            new LoadBalancerDefault(),
            new CloudHttpClientDefault.Wiring(
                List.of(), null, null, null, TransportSecurity.NONE, null, null,
                requestTimeout, Duration.ofSeconds(2), grace));
    }

    private static AppRuntime server(ModuleEx routes) {
        return FreewayApp.run(new HttpModule(), routes);
    }

    /** Blocks inside the handler until the test releases it. */
    static final class BlockingHandlers implements ModuleEx {
        private final CountDownLatch started;
        private final CountDownLatch release;

        BlockingHandlers(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public void bind(Binder binder) {
            binder.contribute(Route.class).add(Route.get("/slow", ctx -> {
                started.countDown();
                awaitQuietly(release);
                ctx.send(200, "done");
            }));
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean chainContains(Throwable failure, String text) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
