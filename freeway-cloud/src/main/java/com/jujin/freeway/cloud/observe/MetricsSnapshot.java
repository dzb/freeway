package com.jujin.freeway.cloud.observe;

/**
 * Rendered-text view of a {@code Metrics} registry. Implemented by
 * registries that can expose themselves for scraping (the built-in
 * {@code MetricsDefault} renders Prometheus text); {@code /metrics} serves
 * whichever primary {@code Metrics} carries this capability, so replacing
 * the registry (e.g. an ext backend) reroutes the export automatically.
 */
public interface MetricsSnapshot {

    /** Renders the registry's current state as scrapeable text. */
    String prometheusText();
}
