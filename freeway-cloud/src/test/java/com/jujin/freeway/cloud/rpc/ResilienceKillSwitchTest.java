package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The aggregate resilience switch {@code freeway.cloud.rpc.resilience}:
 * {@code off} is a kill switch — no retry, NOOP breaker, unlimited limiter —
 * that overrides every fine-grained {@code rpc.*} resilience key; an unknown
 * value fails startup naming the key.
 */
class ResilienceKillSwitchTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.RPC_RESILIENCE);
        System.clearProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS);
        System.clearProperty(CloudConfigKeys.RPC_RETRY_BACKOFF_BASE);
        System.clearProperty(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED);
        System.clearProperty(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND);
        CloudHttpClientTest.CountingFailModule.reset();
    }

    @Test
    void offOverridesFineGrainedRetryKeys() {
        System.setProperty(CloudConfigKeys.RPC_RESILIENCE, "off");
        // A generous retry budget that the kill switch must ignore.
        System.setProperty(CloudConfigKeys.RPC_RETRY_MAX_ATTEMPTS, "5");
        System.setProperty(CloudConfigKeys.RPC_RETRY_BACKOFF_BASE, "10");
        try (AppRuntime app = FreewayApp.run(
                new CloudHttpClientTest.CountingFailModule(), new HttpModule(), new CloudModule())) {
            WebServerHolder.register(app, "failing");

            CloudException ex = assertThrows(CloudException.class, () ->
                app.get(CloudHttpClient.class).call("failing", CloudRequest.get("/api/fail")));
            assertEquals(1, CloudHttpClientTest.CountingFailModule.gets.get(),
                "off must override rpc.retry.max-attempts — ambiguous outcomes are not replayed");
        }
    }

    @Test
    void offKeepsBreakerAndLimiterInert() {
        System.setProperty(CloudConfigKeys.RPC_RESILIENCE, "off");
        // Both would reject rapid calls on their own (1/s limiter, breaker
        // counting successes/failures) — under the kill switch neither fires.
        System.setProperty(CloudConfigKeys.RPC_RATE_LIMIT_ENABLED, "true");
        System.setProperty(CloudConfigKeys.RPC_RATE_LIMIT_PER_SECOND, "1");
        try (AppRuntime app = FreewayApp.run(
                new CloudHttpClientTest.EchoModule(), new HttpModule(), new CloudModule())) {
            WebServerHolder.register(app, "echo");
            CloudHttpClient client = app.get(CloudHttpClient.class);

            for (int i = 1; i <= 6; i++) {
                assertEquals(200,
                    client.call("echo", CloudRequest.get("/api/echo")).status(),
                    "call " + i + ": neither limiter nor breaker may reject under 'off'");
            }
        }
    }

    @Test
    void unknownModeValueFailsStartupNamingTheKey() {
        System.setProperty(CloudConfigKeys.RPC_RESILIENCE, "yolo");
        // The startup hook validates the mode before anything can use it —
        // run() itself must fail, naming the key and the offending value.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            FreewayApp.run(new CloudHttpClientTest.EchoModule(), new HttpModule(), new CloudModule()));
        assertTrue(rootMessage(failure).contains("rpc.resilience"),
            "the failure must name the key: " + rootMessage(failure));
        assertTrue(rootMessage(failure).contains("yolo"),
            "the failure must show the offending value: " + rootMessage(failure));
    }

    /** Scans the exception chain — the container may wrap binding failures. */
    private static String rootMessage(Throwable failure) {
        StringBuilder seen = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            seen.append(t.getMessage()).append(" | ");
        }
        return seen.toString();
    }

    /** Registers this app's own server as a service instance (test shortcut). */
    private static final class WebServerHolder {
        static void register(AppRuntime app, String serviceId) {
            var server = app.get(com.jujin.freeway.http.WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of(serviceId, "i1",
                    Endpoint.of("http", server.host(), server.port()), java.util.Map.of()));
        }
    }
}
