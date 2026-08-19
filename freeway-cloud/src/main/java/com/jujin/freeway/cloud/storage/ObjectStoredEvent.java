package com.jujin.freeway.cloud.storage;

/**
 * Published on the {@code EventBus} after an object is written.
 */
public record ObjectStoredEvent(String bucket, String key, long size, String etag) {
}
