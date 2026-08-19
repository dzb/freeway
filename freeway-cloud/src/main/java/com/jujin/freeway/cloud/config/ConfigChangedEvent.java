package com.jujin.freeway.cloud.config;

/**
 * Published on the {@code EventBus} when a {@link CloudConfig} value changes
 * (reactively rebind consumers). {@code oldValue} is {@code null} for a newly
 * appearing key, {@code newValue} {@code null} for a removed key.
 */
public record ConfigChangedEvent(String key, String oldValue, String newValue) {
}
