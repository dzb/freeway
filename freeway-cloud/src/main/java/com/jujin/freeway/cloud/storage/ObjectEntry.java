package com.jujin.freeway.cloud.storage;

import java.time.Instant;

/**
 * One listed object.
 *
 * @param key          object key relative to the bucket
 * @param contentLength size in bytes
 * @param lastModified last write time
 * @param etag         content digest when the backend stores it; for the local
 *                     file system this is a size+mtime derived tag (not a
 *                     content hash)
 */
public record ObjectEntry(String key, long contentLength, Instant lastModified, String etag) {
}
