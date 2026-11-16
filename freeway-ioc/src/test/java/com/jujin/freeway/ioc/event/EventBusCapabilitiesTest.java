package com.jujin.freeway.ioc.event;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void theChannelIsPartOfTheMethodName() throws Exception {
        assertNotNull(EventBus.class.getMethod("publishEvent", Object.class));
        assertNotNull(EventBus.class.getMethod("publishTopic", String.class, Object.class));
        assertNotNull(EventBus.class.getMethod("publishEventAsync", Object.class));
        assertNotNull(EventBus.class.getMethod("publishTopicAsync", String.class, Object.class));
        assertNotNull(EventBus.class.getMethod("publishEventOrdered", Object.class));
        assertNotNull(EventBus.class.getMethod("publishTopicOrdered", String.class, Object.class));
        assertNotNull(EventBus.class.getMethod(
            "subscribeEvent", Class.class, java.util.function.Consumer.class));
        assertNotNull(EventBus.class.getMethod(
            "subscribeTopic", String.class, java.util.function.Consumer.class));
        assertNotNull(EventBus.class.getMethod("streamEvents", Class.class));
        assertNotNull(EventBus.class.getMethod("streamTopic", String.class));

        assertThrows(NoSuchMethodException.class,
            () -> EventBus.class.getMethod("publish", Object.class),
            "the ambiguous bare publish overload must not come back");
        assertThrows(NoSuchMethodException.class,
            () -> EventBus.class.getMethod("publish", String.class, Object.class),
            "the arity-selected topic publish must not come back");
        assertNotNull(EventSubscriber.class.getMethod(
            "ofEvent", Class.class, java.util.function.Consumer.class));
        assertNotNull(EventSubscriber.class.getMethod(
            "ofTopic", String.class, java.util.function.Consumer.class));
        assertThrows(NoSuchMethodException.class,
            () -> EventSubscriber.class.getMethod(
                "of", Class.class, java.util.function.Consumer.class),
            "a bare of() factory would reintroduce the ambiguity");
    }

    @Test
    void theAssemblyFactoryStillSeesCompositionContributions() {
        // The capability list is a snapshot, so it must be taken after the
        // contributions are sealed — which is what create() promises.
        List<String> received = new ArrayList<>();
        try (Container container = Freeway.create(binder ->
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.ofEvent(String.class, received::add)))) {
            EventBus bus = EventBus.create(container);
            bus.publishEvent("hello");
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
