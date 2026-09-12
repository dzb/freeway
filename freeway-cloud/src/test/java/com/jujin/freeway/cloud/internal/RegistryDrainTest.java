package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shutdown drain window: {@code auto} (the default) asks the bound
 * registry, an explicit duration wins, and a negative one is a config error
 * rather than a sleep that throws at shutdown.
 */
class RegistryDrainTest {

    private static ServiceRegistry registry(Duration window) {
        return new ServiceRegistry() {
            @Override
            public void register(ServiceInstance instance) {
            }

            @Override
            public boolean renew(String serviceId, String instanceId) {
                return true;
            }

            @Override
            public void unregister(ServiceInstance instance) {
            }

            @Override
            public Duration drainWindow() {
                return window;
            }
        };
    }

    /** Only the explicit path resolves through symbols — the coercer is required. */
    private static SymbolSource symbols(Map<String, String> values) {
        return new SymbolSource() {
            @Override
            public String resolve(String name) {
                String value = values.get(name);
                if (value == null) {
                    throw new UnknownSymbolException(name);
                }
                return value;
            }

            @Override
            public <T> T resolve(SymbolSpec<T> spec) {
                String raw;
                try {
                    raw = resolve(spec.key());
                } catch (UnknownSymbolException e) {
                    raw = null;
                }
                return spec.parse(raw, new CoercerDefault());
            }

            @Override
            public String expand(String input) {
                return input;
            }
        };
    }

    @Test
    void autoAndUnsetBothAskTheRegistry() {
        assertEquals(Duration.ofSeconds(5),
            RegistryLifecycleHook.resolveDrain("auto", registry(Duration.ofSeconds(5)), symbols(Map.of())));
        assertEquals(Duration.ofSeconds(5),
            RegistryLifecycleHook.resolveDrain(null, registry(Duration.ofSeconds(5)), symbols(Map.of())));
        assertEquals(Duration.ofSeconds(5),
            RegistryLifecycleHook.resolveDrain(" AUTO ", registry(Duration.ofSeconds(5)), symbols(Map.of())));
    }

    @Test
    void inProcessRegistryAnswersZero() {
        assertEquals(Duration.ZERO,
            RegistryLifecycleHook.resolveDrain("auto", registry(Duration.ZERO), symbols(Map.of())));
    }

    @Test
    void registryWithNoOpinionFallsBackToZero() {
        assertEquals(CloudConfigKeys.REGISTRY_SHUTDOWN_DRAIN_DEFAULT,
            RegistryLifecycleHook.resolveDrain("auto", registry(null), symbols(Map.of())));
    }

    @Test
    void explicitDurationWinsOverTheRegistry() {
        assertEquals(Duration.ofSeconds(2),
            RegistryLifecycleHook.resolveDrain("2s", registry(Duration.ofSeconds(30)),
                symbols(Map.of(CloudConfigKeys.REGISTRY_SHUTDOWN_DRAIN, "2s"))));
    }

    @Test
    void negativeExplicitDurationFailsNamingTheKey() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RegistryLifecycleHook.resolveDrain("-1s", registry(Duration.ZERO),
                symbols(Map.of(CloudConfigKeys.REGISTRY_SHUTDOWN_DRAIN, "-1s"))));
        assertTrue(failure.getMessage().contains("registry.shutdown-drain"),
            "the failure must name the key: " + failure.getMessage());
    }
}
