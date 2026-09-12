package com.jujin.freeway.cloud.storage;

/**
 * Result of a successful {@link ObjectStorage#put}.
 *
 * <p>Deliberately just the content digest: the interface has no
 * version-addressed read or delete, so a version identifier would be a value
 * the caller could only print. Versioning arrives with the operations that
 * make it usable, not before.
 *
 * @param etag content digest (SHA-256 hex)
 */
public record PutResult(String etag) {
}
