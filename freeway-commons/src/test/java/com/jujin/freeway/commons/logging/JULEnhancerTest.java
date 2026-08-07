package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JULEnhancerTest {

    @Test
    void envKeyForUsesDefaultPrefix() {
        assertEquals("FREEWAY_LOG_LEVEL",
            JULEnhancer.envKeyFor("freeway.log.level"));
        assertEquals("FREEWAY_LOG_FILE_MAX_SIZE",
            JULEnhancer.envKeyFor("freeway.log.file.max.size"));
        assertEquals("FREEWAY_LOG_COLOR",
            JULEnhancer.envKeyFor("freeway.log.color"));
    }

    @Test
    void envKeyForHonorsCustomPrefix() {
        String prev = System.getProperty("freeway.env.prefix");
        try {
            System.setProperty("freeway.env.prefix", "APP_");
            // Custom prefix wraps the full key: APP_FREEWAY_LOG_LEVEL →
            // (cascade, strip APP_) FREEWAY_LOG_LEVEL → freeway.log.level.
            assertEquals("APP_FREEWAY_LOG_LEVEL",
                JULEnhancer.envKeyFor("freeway.log.level"));
            assertEquals("APP_FREEWAY_LOG_COLOR",
                JULEnhancer.envKeyFor("freeway.log.color"));
        } finally {
            if (prev == null) {
                System.clearProperty("freeway.env.prefix");
            } else {
                System.setProperty("freeway.env.prefix", prev);
            }
        }
    }
}
