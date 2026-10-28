package com.jujin.freeway.ioc.event;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The bus is handed to application code and lives as long as the container,
 * so it must not hold the container itself: that would hand every subscriber
 * (and every listener a subscriber registers) the whole service graph. Its
 * collaborators are three capabilities — metrics, the sealed subscriber list
 * and an optional carrier — taken once at assembly.
 */
class EventBusCapabilitiesTest {

    @Test
    void neitherTheBusNorItsIndexHoldsAContainer() {
        assertHoldsNoContainer(EventBus.class);
        assertHoldsNoContainer(EventSubscriptionIndex.class);
    }

    @Test
    void theAssemblyFactoryStillSeesCompositionContributions() {
        // The capability list is a snapshot, so it must be taken after the
        // contributions are sealed — which is what create() promises.
        List<String> received = new ArrayList<>();
        try (Container container = Freeway.create(binder ->
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of(String.class, received::add)))) {
            EventBus bus = EventBus.create(container);
            bus.publish("hello");
        }
        assertEquals(List.of("hello"), received,
            "a contributed subscriber must reach the bus built by the factory");
    }

    private static void assertHoldsNoContainer(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertNotEquals(Container.class, field.getType(),
                type.getSimpleName() + "." + field.getName()
                    + " holds the container: the bus must receive capabilities");
        }
    }
}
