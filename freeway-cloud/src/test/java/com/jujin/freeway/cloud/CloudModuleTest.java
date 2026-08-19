package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.annotation.RoundRobin;
import com.jujin.freeway.cloud.config.CloudConfig;
import com.jujin.freeway.cloud.config.CloudConfigModule;
import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.health.CloudHealthModule;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.cloud.observe.MeterRegistry;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CloudResilienceModule;
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
            new CloudConfigModule(),
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
            assertNotNull(container.get(CloudConfig.class));
            assertNotNull(container.get(SecretStore.class));
            assertNotNull(container.get(Tracer.class));
            assertNotNull(container.get(MeterRegistry.class));

            // Marker-based selection (@Local default, @RoundRobin strategy)
            assertNotNull(container.get(ServiceDiscovery.class, Local.class));
            assertNotNull(container.get(LoadBalancer.class, RoundRobin.class));
        }
    }
}
