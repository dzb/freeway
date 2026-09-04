package com.jujin.freeway.cloud.observe;

/**
 * Rendered-text view of a {@code Metrics} registry. Implemented by
 * registries that can expose themselves for scraping (the built-in
 * {@code MetricsDefault} renders Prometheus text).
 *
 * <p>{@code CloudObserveModule} binds its own {@code MetricsDefault}
 * instance under both {@code Metrics} and {@code MetricsSnapshot}, and the
 * {@code /metrics} route serves whichever {@code MetricsSnapshot} binding is
 * active. A replacement metrics backend therefore binds its registry under
 * this interface itself (and contributes its own route) — the container
 * never exposes a {@code Metrics} service whose proxy carries extra
 * interfaces, so no binding can be derived from a {@code Metrics}
 * implementation by {@code instanceof}.
 */
public interface MetricsSnapshot {

    /** Renders the registry's current state as scrapeable text. */
    String prometheusText();
}
