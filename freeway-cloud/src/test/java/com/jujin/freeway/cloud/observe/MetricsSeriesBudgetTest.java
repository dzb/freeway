package com.jujin.freeway.cloud.observe;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Label misuse must fail at registration instead of growing the in-memory
 * registry (and the scrape payload) without bound.
 */
class MetricsSeriesBudgetTest {

    @Test
    void exceedingTheBudgetFailsLoudlyAndKeepsExistingSeries() {
        MetricsDefault metrics = new MetricsDefault(2);
        metrics.counter("a").increment();
        metrics.counter("b").increment();

        var failure = assertThrows(IllegalArgumentException.class,
            () -> metrics.counter("c"));
        assertTrue(failure.getMessage().contains("budget"), failure.getMessage());
        assertTrue(failure.getMessage().contains("'c'"), failure.getMessage());

        // Re-registering an admitted set is not a new series.
        metrics.counter("a").increment();
        assertTrue(metrics.prometheusText().contains("a 2"));
    }

    @Test
    void aHistogramLabelSetCountsAsOneMeterSet() {
        MetricsDefault metrics = new MetricsDefault(1);
        metrics.histogram("h", Map.of("route", "/a")).record(1_000);

        assertThrows(IllegalArgumentException.class,
            () -> metrics.histogram("h", Map.of("route", "/b")));
    }
}
