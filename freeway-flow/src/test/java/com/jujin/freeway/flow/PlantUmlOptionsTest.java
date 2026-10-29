package com.jujin.freeway.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PlantUML options value: defaults are stated once, and each wither returns
 * a new value without touching the one it came from.
 */
class PlantUmlOptionsTest {

    @Test
    void defaultsStateBothFields() {
        PlantUmlOptions options = PlantUmlOptions.defaults();
        assertTrue(options.showGatewayType(), "gateway type is shown by default");
        assertFalse(options.showIdInTitle(), "the graph id is not appended by default");
    }

    @Test
    void withersReturnANewValueAndLeaveTheOriginalAlone() {
        PlantUmlOptions defaults = PlantUmlOptions.defaults();
        PlantUmlOptions custom = defaults.withShowGatewayType(false).withShowIdInTitle(true);

        assertFalse(custom.showGatewayType());
        assertTrue(custom.showIdInTitle());
        // The original is unchanged, so a shared default cannot be mutated by one caller.
        assertTrue(defaults.showGatewayType());
        assertFalse(defaults.showIdInTitle());
        assertSame(defaults, defaults.withShowGatewayType(true),
            "setting a field to its current value returns the same instance");
    }
}
