package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.health.CloudHealthModule;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CloudResilienceModule;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.rpc.CloudHttpClient;
import com.jujin.freeway.cloud.rpc.CloudRpcModule;
import com.jujin.freeway.cloud.secret.CloudSecretModule;
import com.jujin.freeway.cloud.secret.SecretStore;
import com.jujin.freeway.cloud.storage.CloudStorageModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0/1 scaffold acceptance: the umbrella module and every sub-module must
 * install; all core defaults must resolve via IoC ({@code @Local} +
 * {@code .primary()}).
 */
class CloudModuleTest {

    @Test
    void umbrellaModuleInstalls() {
        try (Container container = Freeway.create(new CloudModule())) {
            // Construction + container start is the acceptance check at scaffold stage.
        }
    }

    @Test
    void eachSubModuleInstallsIndependently() {
        List<ModuleEx> modules = List.of(
            new CloudContextModule(),
            new CloudSecretModule(),
            new CloudDiscoveryModule(),
            new CloudRpcModule(),
            new CloudObserveModule(),
            new CloudResilienceModule(),
            new CloudHealthModule(),
            new CloudStorageModule());
        for (ModuleEx module : modules) {
            try (Container container = Freeway.create(module)) {
                // Every sub-module must be installable on its own (subset assembly).
            }
        }
    }

    @Test
    void defaultsResolveFromUmbrellaModule() {
        try (Container container = Freeway.create(new CloudModule())) {
            assertNotNull(container.get(ServiceRegistry.class));
            assertNotNull(container.get(ServiceDiscovery.class));
            assertNotNull(container.get(CloudHttpClient.class));
            assertNotNull(container.get(SecretStore.class));
            assertNotNull(container.get(Tracer.class));
            assertNotNull(container.get(Metrics.class));

            // Marker-based selection: @Local marks every built-in default —
            // the single selector extension modules replace with a primary binding.
            assertNotNull(container.get(ServiceDiscovery.class, Local.class));
            assertNotNull(container.get(LoadBalancer.class, Local.class));
        }
    }

    @Test
    void rateLimitingIsUnlimitedByDefault() {
        // rate-limit.enabled defaults to false; without any config the
        // resolved limiter must be the no-op singleton, not a 100 req/s
        // token bucket from the library fallback.
        try (Container container = Freeway.create(new CloudResilienceModule())) {
            RateLimiter limiter = container.get(RateLimiter.class);
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
        }
    }
}
