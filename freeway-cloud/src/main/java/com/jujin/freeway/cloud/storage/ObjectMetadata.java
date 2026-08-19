package com.jujin.freeway.cloud.storage;

import java.util.Map;

/**
 * Object metadata supplied on write.
 *
 * @param contentType   media type, or empty string
 * @param contentLength expected length (implementations may use the actual bytes)
 * @param userMetadata  application metadata (immutable)
 */
public record ObjectMetadata(String contentType, long contentLength, Map<String, String> userMetadata) {

    public ObjectMetadata {
        contentType = contentType == null ? "" : contentType;
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public static ObjectMetadata of(String contentType) {
        return new ObjectMetadata(contentType, -1, Map.of());
    }
}
