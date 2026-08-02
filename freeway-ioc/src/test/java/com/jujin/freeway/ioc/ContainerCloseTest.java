package com.jujin.freeway.ioc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ContainerCloseTest {

    @Test
    void closeIsIdempotent() {
        Container container = Freeway.create();
        container.close();
        assertDoesNotThrow(container::close,
            "Repeated close() must not re-run shutdown or PreDestroy");
    }
}
