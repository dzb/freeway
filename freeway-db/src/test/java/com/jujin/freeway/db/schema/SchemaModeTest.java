package com.jujin.freeway.db.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SchemaModeTest {

    @Test
    void parsesAllModesCaseInsensitively() {
        assertEquals(SchemaMode.AUTO, SchemaMode.of("auto"));
        assertEquals(SchemaMode.VALIDATE, SchemaMode.of(" Validate "));
        assertEquals(SchemaMode.OFF, SchemaMode.of("OFF"));
    }

    @Test
    void rejectsUnknownModeNamingValidValues() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SchemaMode.of("sometimes"));
        assertTrue(ex.getMessage().contains("auto, validate, off"), ex.getMessage());
        assertTrue(ex.getMessage().contains("freeway.db.schema.mode"), ex.getMessage());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> SchemaMode.of(null));
    }
}
