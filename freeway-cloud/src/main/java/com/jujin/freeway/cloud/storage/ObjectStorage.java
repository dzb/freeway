package com.jujin.freeway.cloud.storage;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Object storage (synchronous API, virtual-thread friendly — follows the
 * {@code Database}/{@code Pool} pattern). Optional capability, decoupled from
 * the discovery/rpc/config/observe/resilience chain.
 */
public interface ObjectStorage {

    /** Reads an object; empty when absent. */
    Optional<byte[]> get(String bucket, String key) throws StorageException;

    /** Writes an object; returns its etag and version id. */
    PutResult put(String bucket, String key, byte[] data, ObjectMetadata metadata) throws StorageException;

    /** Deletes an object; absent keys are a no-op. */
    void delete(String bucket, String key) throws StorageException;

    /** Lists objects in {@code bucket} whose key starts with {@code prefix}. */
    List<ObjectEntry> list(String bucket, String prefix) throws StorageException;

    /**
     * Pre-signed URL for temporary access, when the backend supports it.
     * The local file system has no signing semantics — empty by default;
     * object-store backends (S3) provide it.
     */
    default Optional<URL> presignedUrl(String bucket, String key, Duration ttl) {
        return Optional.empty();
    }
}
