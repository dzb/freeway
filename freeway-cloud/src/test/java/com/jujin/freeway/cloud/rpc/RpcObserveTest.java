package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.observe.MetricsDefault;
import com.jujin.freeway.cloud.discovery.internal.RegistryStore;
import com.jujin.freeway.cloud.discovery.ServiceDiscoveryDefault;
import com.jujin.freeway.cloud.observe.TracerDefault;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import com.jujin.freeway.http.HttpModule.ConfigKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC → observability wiring: RpcHttpClient calls record {@code cloud.rpc.*}
 * metrics and (when tracing is enabled) create spans. The client is wired by
 * hand instead of through {@code RpcModule}, because the container hands
 * out a proxied {@code MetricsDefault} whose snapshot accessors the proxy does
 * not expose — the module-level assembly is covered by the container tests.
 */
class RpcObserveTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(ConfigKeys.SERVER_PORT);
    }

    private static RpcHttpClientDefault client(
        HttpServer server, MetricsDefault metrics, TracerDefault tracer) {
        var store = new RegistryStore();
        store.register(ServiceInstance.of("echo", "i1",
            ServiceEndpoint.of("http", server.host(), server.port()), Map.of()));
        return new RpcHttpClientDefault(
            new ServiceDiscoveryDefault(store),
            new LoadBalancerDefault(),
            RpcHttpClientDefault.Wiring.defaults()
                .withTracer(tracer)
                .withMetrics(metrics));
    }

    @Test
    void successfulCallsAreCountedAgainstRpcMetrics() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new RpcHttpClientTest.EchoModule(), new HttpModule())) {
            var metrics = new MetricsDefault();
            RpcHttpClientDefault client = client(app.get(HttpServer.class), metrics, new TracerDefault());

            RpcResponse resp = client.call("echo", RpcRequest.get("/api/echo"));
            assertTrue(resp.isSuccess());

            assertEquals(1, metrics.counterValue("cloud.rpc.calls", Map.of("service", "echo")),
                "each successful call must increment cloud.rpc.calls");
            assertEquals(0, metrics.counterValue("cloud.rpc.failures", Map.of("service", "echo")),
                "a 2xx call must not count as a failure");
            assertEquals(1, metrics.histogramCount("cloud.rpc.duration", Map.of("service", "echo")),
                "the call duration must be recorded");
        }
    }

    @Test
    void metricsAreRecordedWhenTracingIsDisabled() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new RpcHttpClientTest.EchoModule(), new HttpModule())) {
            var metrics = new MetricsDefault();
            // tracer == null mirrors freeway.cloud.rpc.trace.enabled=false.
            RpcHttpClientDefault client = client(app.get(HttpServer.class), metrics, null);

            RpcResponse resp = client.call("echo", RpcRequest.get("/api/echo"));
            assertTrue(resp.isSuccess());

            assertEquals(1, metrics.counterValue("cloud.rpc.calls", Map.of("service", "echo")),
                "metrics must stay wired when tracing is disabled");
        }
    }
}
