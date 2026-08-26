package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.CloudHttpClientDefault;
import com.jujin.freeway.cloud.internal.LoadBalancerDefault;
import com.jujin.freeway.cloud.internal.MetricsDefault;
import com.jujin.freeway.cloud.internal.RegistryStore;
import com.jujin.freeway.cloud.internal.ServiceDiscoveryDefault;
import com.jujin.freeway.cloud.internal.TracerDefault;
import com.jujin.freeway.cloud.rpc.CloudRequest;
import com.jujin.freeway.cloud.rpc.CloudResponse;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC → observability wiring: CloudHttpClient calls record {@code cloud.rpc.*}
 * metrics and (when tracing is enabled) create spans. The client is wired by
 * hand instead of through {@code CloudRpcModule}, because the container hands
 * out a proxied {@code MetricsDefault} whose snapshot accessors the proxy does
 * not expose — the module-level assembly is covered by the container tests.
 */
class RpcObserveTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    private static CloudHttpClientDefault client(
        WebServer server, MetricsDefault metrics, TracerDefault tracer) {
        var store = new RegistryStore();
        store.register(ServiceInstance.of("echo", "i1",
            Endpoint.of("http", server.host(), server.port()), Map.of()));
        return new CloudHttpClientDefault(
            new ServiceDiscoveryDefault(store),
            new LoadBalancerDefault(),
            List.of(),
            null,
            null,
            null,
            null,
            tracer,
            metrics,
            Duration.ofSeconds(10),
            Duration.ofSeconds(3));
    }

    @Test
    void successfulCallsAreCountedAgainstCloudRpcMetrics() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new CloudHttpClientTest.EchoModule(), new HttpModule())) {
            var metrics = new MetricsDefault();
            CloudHttpClientDefault client = client(app.get(WebServer.class), metrics, new TracerDefault());

            CloudResponse resp = client.call("echo", CloudRequest.get("/api/echo"));
            assertTrue(resp.is2xx());

            assertEquals(1, metrics.counterValue("cloud.rpc.calls"),
                "each successful call must increment cloud.rpc.calls");
            assertEquals(0, metrics.counterValue("cloud.rpc.failures"),
                "a 2xx call must not count as a failure");
            assertEquals(1, metrics.timerCount("cloud.rpc.duration"),
                "the call duration must be recorded");
        }
    }

    @Test
    void metricsAreRecordedWhenTracingIsDisabled() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new CloudHttpClientTest.EchoModule(), new HttpModule())) {
            var metrics = new MetricsDefault();
            // tracer == null mirrors freeway.cloud.rpc.trace.enabled=false.
            CloudHttpClientDefault client = client(app.get(WebServer.class), metrics, null);

            CloudResponse resp = client.call("echo", CloudRequest.get("/api/echo"));
            assertTrue(resp.is2xx());

            assertEquals(1, metrics.counterValue("cloud.rpc.calls"),
                "metrics must stay wired when tracing is disabled");
        }
    }
}
