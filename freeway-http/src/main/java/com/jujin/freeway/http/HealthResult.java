package com.jujin.freeway.http;

import java.util.Objects;

/**
 * Result of one health check. The same value type is consumed by the HTTP
 * health endpoint and by cloud readiness contributors, so a custom check and
 * a cloud dependency report the same fact with the same fields.
 */
public record HealthResult(boolean healthy, String detail) {

    public HealthResult {
        detail = detail == null ? "" : detail;
    }

    public static HealthResult ok() {
        return new HealthResult(true, "");
    }

    public static HealthResult unhealthy(String detail) {
        return new HealthResult(false, Objects.requireNonNull(detail, "detail"));
    }
}
