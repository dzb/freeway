package com.jujin.freeway2.web;

import com.jujin.freeway2.ioc.Container;
import com.jujin.freeway2.ioc.Freeway2;
import com.jujin.freeway2.ioc.ServiceId;
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
    void exposesUndertowEngineSeamByServiceId() {
        container = Freeway2.create(new WebModule(), new UndertowWebEngineModule());
        assertInstanceOf(UndertowWebEngine.class, container.get(WebEngine.class, ServiceId.of("undertow")));
    }
}
