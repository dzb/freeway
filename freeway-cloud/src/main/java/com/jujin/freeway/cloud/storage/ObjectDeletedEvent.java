package com.jujin.freeway.cloud.storage;

/**
 * Published on the {@code EventBus} after an object is deleted.
 */
public record ObjectDeletedEvent(String bucket, String key) {
}
