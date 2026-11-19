package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.discovery.TestDiscoveryModule;
import com.jujin.freeway.cloud.resilience.ResilienceModule;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client-level contract of passive ejection: a dead endpoint beside a live
 * one keeps failing its turn until the threshold, then stops being offered to
 * the load balancer — retries and later calls never see it again. A caller
 * timeout is a separate contract: it must never charge the instance.
 */
class InstanceEjectionClientTest {

    private static final int CALLS = 20;
    private static final int THRESHOLD =
        ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD_DEFAULT;

    public static class Handlers {
        public int add(int a, int b) {
            return a + b;
        }

        public String slow() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }
    }

    static class CalcExportModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Handlers.class);
            binder.contribute(RpcExport.class).add(RpcExport.of("calc", Handlers.class));
        }
    }

    /** Records the candidate set and the chosen instance of every choice. */
    static final class RecordingBalancer implements LoadBalancer {
        private final LoadBalancer delegate = new LoadBalancerDefault();
        final List<Set<String>> candidates = Collections.synchronizedList(new ArrayList<>());
        final List<String> picks = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<ServiceInstance> choose(List<ServiceInstance> instances) {
            Set<String> ids = new LinkedHashSet<>();
            for (ServiceInstance instance : instances) {
                ids.add(instance.instanceId());
            }
            candidates.add(ids);
            Optional<ServiceInstance> chosen = delegate.choose(instances);
            picks.add(chosen.map(ServiceInstance::instanceId).orElse(null));
            return chosen;
        }
    }

    static final class RecordingBalancerModule implements ModuleEx {
        private final RecordingBalancer balancer;

        RecordingBalancerModule(RecordingBalancer balancer) {
            this.balancer = balancer;
        }

        @Override
        public void bind(Binder binder) {
            binder.bind(LoadBalancer.class).to(container -> balancer);
        }
    }

    private AppRuntime server;
    private RemoteCaller caller;
    private RecordingBalancer balancer;

    @BeforeEach
    void start() throws Exception {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        System.setProperty(RpcModule.ConfigKeys.RPC_REQUEST_TIMEOUT, "2000");
        balancer = new RecordingBalancer();
        server = FreewayApp.create(new HttpModule())
            .add(CloudModule.class)
            .add(new TestDiscoveryModule())
            .add(new CalcExportModule())
            .add(new RecordingBalancerModule(balancer))
            .start();
        caller = server.get(RemoteCaller.class);

        var webServer = server.get(com.jujin.freeway.http.HttpServer.class);
        ServiceRegistry registry = server.get(ServiceRegistry.class);
        registry.register(ServiceInstance.of(
            "target", "live",
            ServiceEndpoint.of("http", webServer.host(), webServer.port())));
        registry.register(ServiceInstance.of(
            "target", "dead", ServiceEndpoint.of("http", "127.0.0.1", closedPort())));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(RpcModule.ConfigKeys.RPC_REQUEST_TIMEOUT);
    }

    @Test
    void aDeadInstanceStopsBeingOfferedAfterTheThreshold() {
        for (int i = 0; i < CALLS; i++) {
            assertEquals(3, caller.invoke("target", "calc", "add", List.of(1, 2), Integer.class),
                "connect failures are retryable, so every call still succeeds");
        }

        int deadPicks = 0;
        int cutoff = -1;
        for (int i = 0; i < balancer.picks.size(); i++) {
            if ("dead".equals(balancer.picks.get(i))) {
                deadPicks++;
                if (deadPicks == THRESHOLD) {
                    cutoff = i;
                }
            }
        }
        assertTrue(cutoff >= 0,
            "the dead endpoint must have been tried " + THRESHOLD + " times (dead picks="
                + deadPicks + "): " + balancer.picks);

        for (int i = cutoff + 1; i < balancer.candidates.size(); i++) {
            assertFalse(balancer.candidates.get(i).contains("dead"),
                "after ejection the dead endpoint is never offered again (i=" + i
                    + " cutoff=" + cutoff + "): " + balancer.candidates);
        }
        assertEquals(Set.of("live"), balancer.candidates.getLast());
        assertEquals("live", balancer.picks.getLast());
    }

    @Test
    void aTimeoutInsideTheCallersDeadlineIsNotChargedToTheInstance() throws Exception {
        var webServer = server.get(com.jujin.freeway.http.HttpServer.class);
        ServiceRegistry registry = server.get(ServiceRegistry.class);
        // Two members on one healthy server: if the timeouts counted, each
        // would collect enough failures to cross the threshold and eject.
        registry.register(ServiceInstance.of(
            "slow", "slow-a", ServiceEndpoint.of("http", webServer.host(), webServer.port())));
        registry.register(ServiceInstance.of(
            "slow", "slow-b", ServiceEndpoint.of("http", webServer.host(), webServer.port())));

        for (int i = 0; i < 12; i++) {
            assertThrows(RpcException.class, () -> caller.invoke(
                    "slow", "calc", "slow", List.of(), String.class, Duration.ofMillis(100)),
                "the handler (300ms) outlives the caller's 100ms budget");
        }

        assertEquals(Set.of("slow-a", "slow-b"), balancer.candidates.getLast(),
            "the caller's deadline is not the instance's fault: no member is ejected ("
                + balancer.candidates + ")");
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
