package com.jujin.freeway.cloud.health;

import java.util.Objects;

/**
 * Result of one {@link CloudHealthContributor} check.
 */
public record HealthResult(boolean healthy, String detail) {

    public static HealthResult ok() {
        return new HealthResult(true, "");
    }

    public static HealthResult unhealthy(String detail) {
        return new HealthResult(false, Objects.requireNonNull(detail, "detail"));
    }
}
