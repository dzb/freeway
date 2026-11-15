package com.jujin.freeway.ioc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OptionalBindingTest {

    static final class Service {}
    interface Dependency {}

    @Test
    void absentBindingIsNull() {
        try (Container container = Freeway.create()) {
            assertNull(OptionalBinding.get(container, Service.class));
        }
    }

    @Test
    void providerFailureIsNotAnAbsentBinding() {
        try (Container container = Freeway.create(binder ->
                binder.bind(Service.class).to(c -> {
                    c.get(Dependency.class); // nested failure, not an absent Service
                    return new Service();
                }))) {
            assertThrows(MissingBindingException.class,
                () -> OptionalBinding.get(container, Service.class),
                "a broken provider must propagate, not degrade to null");
        }
    }
}
