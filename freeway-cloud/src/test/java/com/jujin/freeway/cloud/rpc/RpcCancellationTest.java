package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.resilience.RetryerDefault;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Cancelling the returned future is not cosmetic: it interrupts the worker
 * (including retry backoff) and asks the in-flight send to stop. Without it a
 * cancelled call keeps retrying for a result nobody will read.
 */
class RpcCancellationTest {

    @Test
    void cancellingTheFutureStopsTheRetryLoop() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger discoveries = new AtomicInteger();
        CountDownLatch firstSeen = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fail", exchange -> {
            requests.incrementAndGet();
            firstSeen.countDown();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        RpcHttpClientDefault client = new RpcHttpClientDefault(
            serviceId -> {
                discoveries.incrementAndGet();
                return List.of(ServiceInstance.of("svc", "i1",
                    ServiceEndpoint.of("http", "127.0.0.1", server.getAddress().getPort()),
                    Map.of()));
            },
            new LoadBalancerDefault(),
            new RpcHttpClientDefault.Wiring(
                List.of(), new RetryerDefault(3, 600, 600), null, null,
                TransportSecurity.NONE, null, null,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(200)));
        try {
            var future = client.callAsync("svc", RpcRequest.get("/fail"));
            assertTrue(firstSeen.await(5, TimeUnit.SECONDS),
                "the first attempt must reach the server");

            // Give the 500 time to cross the retry decision and enter backoff.
            Thread.sleep(150);
            assertTrue(future.cancel(true), "the caller's future must be cancellable");

            // Longer than the 600 ms backoff: an interrupt stops it, a plain
            // sleep-and-retry would issue the second request by now.
            Thread.sleep(900);
            assertEquals(1, requests.get(),
                "cancellation must interrupt the retry backoff, not run another attempt");
            assertEquals(1, discoveries.get(),
                "an interrupted worker must not even rediscover for a retry");
            assertTrue(future.isCancelled());
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
