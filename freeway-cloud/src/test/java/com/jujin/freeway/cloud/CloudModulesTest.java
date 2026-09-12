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
import com.jujin.freeway.ioc.ModuleNode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud bundle is a composition fragment: one value holding the eight
 * standard modules, placed in an application tree like any other node — and
 * every module still installs on its own (subset assembly).
 */
class CloudModulesTest {

    private static final List<Class<?>> STANDARD = List.of(
        CloudContextModule.class,
        CloudSecretModule.class,
        CloudDiscoveryModule.class,
        CloudRpcModule.class,
        CloudObserveModule.class,
        CloudResilienceModule.class,
        CloudHealthModule.class,
        CloudStorageModule.class);

    @Test
    void standardFragmentHoldsEveryCloudModule() {
        ModuleNode bundle = CloudModules.standard();

        assertEquals("freeway-cloud", bundle.name());
        assertEquals(STANDARD.size() + 1, bundle.size(), "the group node plus one leaf per module");
        assertTrue(bundle.classes().containsAll(STANDARD),
            "discovery must not add a second instance of any bundled class");
    }

    @Test
    void fragmentIsPlacedInTheApplicationTree() {
        ModuleNode app = ModuleNode.app("order-service",
            ModuleNode.leaf(new AppMarkerModule()),
            CloudModules.standard());

        try (Container container = Freeway.create(app)) {
            assertSame(app, container.moduleTree());
            assertNotNull(container.get(AppMarkerModule.Marker.class));
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
    void aModuleCanBePlacedWithoutTheRestOfTheBundle() {
        // The point of a fragment: taking it apart is normal composition, not a
        // workaround — one cloud module, configured by the application.
        ModuleNode app = ModuleNode.app("test", ModuleNode.leaf(new CloudRpcModule()));

        try (Container container = Freeway.create(app)) {
            assertNotNull(container.get(CloudHttpClient.class));
            assertThrows(com.jujin.freeway.ioc.MissingBindingException.class,
                () -> container.get(SecretStore.class),
                "the rest of the bundle is absent because it was never placed");
        }
    }

    @Test
    void eachModuleInstallsIndependently() {
        for (Class<?> module : STANDARD) {
            ModuleEx instance = newModule(module);
            try (Container container = Freeway.create(ModuleNode.app("test",
                    ModuleNode.leaf(instance)))) {
                // Every module must be installable on its own (subset assembly).
            }
        }
    }

    private static ModuleEx newModule(Class<?> type) {
        try {
            return (ModuleEx) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), e);
        }
    }

    @Test
    void localMarkerDeclaresOnlyPositionsTheFrameworkReads() {
        // Same rule as ioc's annotation-target test: a position without a
        // reader is an affordance the framework does not keep. @Local is read
        // at injection points (through the marker index) and as a marker value
        // in .marker(...)/@Marker(...); ioc's MarkerIndex cannot read a cloud
        // annotation off a class, so TYPE would silently do nothing.
        assertEquals(Set.of(ElementType.FIELD, ElementType.PARAMETER),
            Set.of(Local.class.getAnnotation(Target.class).value()));
    }

    @Test
    void rateLimitingIsUnlimitedByDefault() {
        // rate-limit.enabled defaults to false; without any config the
        // resolved limiter must be the no-op singleton, not a 100 req/s
        // token bucket from the library fallback.
        try (Container container = Freeway.create(
                ModuleNode.app("test", ModuleNode.leaf(new CloudResilienceModule())))) {
            RateLimiter limiter = container.get(RateLimiter.class);
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
        }
    }

    /** A tiny application module, to prove the fragment sits beside app modules. */
    static final class AppMarkerModule implements ModuleEx {

        record Marker() {}

        @Override
        public void bind(com.jujin.freeway.ioc.Binder binder) {
            binder.bind(Marker.class).to(c -> new Marker());
        }
    }
}
