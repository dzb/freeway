package com.jujin.freeway.web;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ServiceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WebEngineSelectionTest {
    private Container container;

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void exposesRobahoEngineSeamByServiceId() {
        container = Freeway.create(new WebModule(), new RobahoWebEngineModule());
        assertInstanceOf(RobahoWebEngine.class, container.get(WebEngine.class, ServiceId.of("robaho")));
    }
}
