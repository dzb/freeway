package com.jujin.freeway.cloud.storage;

/**
 * Result of a successful {@link ObjectStorage#put}.
 *
 * @param etag      content digest (SHA-256 hex)
 * @param versionId version identifier (new per write)
 */
public record PutResult(String etag, String versionId) {
}
