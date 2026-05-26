package com.jujin.freeway.http;

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
    void exposesJettyEngineSeamByServiceId() {
        container = Freeway.create(new HttpModule(), new JettyWebEngineModule());
        assertInstanceOf(JettyWebEngine.class, container.get(HttpEngine.class, ServiceId.of("jetty")));
    }
}
