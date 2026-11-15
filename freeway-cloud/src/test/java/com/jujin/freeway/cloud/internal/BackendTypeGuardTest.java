package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackendTypeGuardTest {

    private static final String KEY = "test.backend.type";

    @Test
    void warnsWhenAnExternalTypeHasNoBinding() {
        try (Container container = Freeway.create()) {
            container.get(SymbolSource.class);
            System.setProperty(KEY, "consul");
            try {
                assertTrue(BackendTypeGuard.warnIfMissing(
                    container, ServiceDiscovery.class, KEY, "discovery"));
            } finally {
                System.clearProperty(KEY);
            }
        }
    }

    @Test
    void doesNotWarnWhenAnImplementationIsBound() {
        try (Container container = Freeway.create(binder ->
                binder.bind(ServiceDiscovery.class)
                    .to(c -> (ServiceDiscovery) serviceId -> List.of()))) {
            System.setProperty(KEY, "consul");
            try {
                assertFalse(BackendTypeGuard.warnIfMissing(
                    container, ServiceDiscovery.class, KEY, "discovery"));
            } finally {
                System.clearProperty(KEY);
            }
        }
    }
}
