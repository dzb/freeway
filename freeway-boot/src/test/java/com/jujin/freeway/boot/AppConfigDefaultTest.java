package com.jujin.freeway.boot;

import com.jujin.freeway.commons.config.ConfigProperty;
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

    @Test
    void typedGetParsesWithDefaultAndErrors() {
        ConfigProperty<Integer> port = ConfigProperty.of(
            "server.port", Integer.class, 8080, Integer::parseInt);
        AppConfig config = new AppConfigDefault(
            new LinkedHashMap<>(Map.of("server.port", "9090")), List.of());

        assertEquals(9090, config.get(port), "raw value parsed to the typed form");
        assertEquals(8080, config.get(
            ConfigProperty.of("missing.port", Integer.class, 8080, Integer::parseInt)),
            "absent key falls back to the default");

        AppConfig blank = new AppConfigDefault(
            new LinkedHashMap<>(Map.of("server.port", "  ")), List.of());
        assertEquals(8080, blank.get(port),
            "a blank raw value falls back to the default");
    }

    @Test
    void typedGetReportsMalformedValueWithKeyContext() {
        ConfigProperty<Integer> port = ConfigProperty.of(
            "server.port", Integer.class, 8080, Integer::parseInt);
        AppConfig config = new AppConfigDefault(
            new LinkedHashMap<>(Map.of("server.port", "not-a-number")), List.of());

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> config.get(port));
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().contains("server.port"),
            "the error must name the offending key, got: " + ex.getMessage());
    }

    @Test
    void requiredKeyFailsFastWhenAbsentOrBlank() {
        ConfigProperty<String> password = ConfigProperty.required(
            "db.password", String.class, String::valueOf);
        AppConfig absent = new AppConfigDefault(
            new LinkedHashMap<>(), List.of());
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> absent.get(password));
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().contains("Missing required") && ex.getMessage().contains("db.password"),
            "got: " + ex.getMessage());

        AppConfig blank = new AppConfigDefault(
            new LinkedHashMap<>(Map.of("db.password", " ")), List.of());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> blank.get(password),
            "a blank required value is equally missing");

        AppConfig present = new AppConfigDefault(
            new LinkedHashMap<>(Map.of("db.password", "s3cret")), List.of());
        assertEquals("s3cret", present.get(password));
    }
}
