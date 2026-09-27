package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The published logging surface: the namespace plus exactly the fixed key names — the vocabulary
 * boot declares for the one configuration the infrastructure modules own.
 */
class LogConfigTest {

    @Test
    void knownKeysAreTheFixedKeysOfTheNamespaceOnly() {
        Set<String> keys = LogConfig.knownKeys();

        assertTrue(keys.contains("freeway.log.level"));
        assertTrue(keys.contains("freeway.log.file.max-size"), "fixed per-default-file keys count");
        assertTrue(keys.contains("freeway.log.console.enabled"));
        // Fragments of the namespace name no key, and app.name sits outside it.
        assertTrue(keys.stream().noneMatch(k -> k.endsWith(".")), keys.toString());
        assertTrue(keys.stream().noneMatch(k -> !k.startsWith(LogConfig.PREFIX)), keys.toString());
        assertTrue(!keys.contains(LogKeys.APP_NAME), "app.name is not a freeway.* key");
    }

    @Test
    void knownKeysIsTheTableItself() {
        // Read off LogKeys, so a key added there is in the vocabulary with no second list to edit.
        assertEquals(Set.of(
            "freeway.log.level",
            "freeway.log.format",
            "freeway.log.color",
            "freeway.log.mdc",
            "freeway.log.mdc.priority",
            "freeway.log.caller-info",
            "freeway.log.console.enabled",
            "freeway.log.console.level",
            "freeway.log.files",
            "freeway.log.file",
            "freeway.log.file.max-size",
            "freeway.log.file.max-history",
            "freeway.log.file.compress",
            "freeway.log.file.flush-interval"), LogConfig.knownKeys());
    }
}
