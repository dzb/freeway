package com.jujin.freeway.boot;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppConfigDefaultTest {

    @Test
    void skipsNullKeysAndValuesFromCustomLoaders() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("good", "value");
        input.put("null-value", null);
        input.put(null, "null-key");

        AppConfig config = new AppConfigDefault(input, List.of());

        assertEquals("value", config.get("good"));
        assertNull(config.get("null-value"));
        assertFalse(config.asMap().containsKey("null-value"));
    }
}
