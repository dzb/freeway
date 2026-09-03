package com.jujin.freeway.ioc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleDedupTest {

    static final class NamedModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Marker.class).to(c -> new Marker("named"));
        }
    }

    record Marker(String value) {
    }

    @Test
    void sameInstanceInstalledTwiceIsIgnored() {
        var module = new NamedModule();
        try (Container container = Freeway.create(module, module)) {
            assertEquals("named", container.get(Marker.class).value(),
                "the same instance must be installed once");
        }
    }

    @Test
    void distinctInstancesOfSameClassFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> Freeway.create(new NamedModule(), new NamedModule()));

        assertTrue(ex.getMessage().contains("installed twice"),
            "message must name the duplicate-module problem: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("SPI auto-discovery"),
            "message must point at the likely cause: " + ex.getMessage());
    }

    @Test
    void distinctModuleClassesBothInstall() {
        try (Container container = Freeway.create(
            binder -> binder.bind(String.class).to(c -> "a"),
            new NamedModule()
        )) {
            assertEquals("a", container.get(String.class));
            assertNotNull(container.get(Marker.class));
        }
    }
}
