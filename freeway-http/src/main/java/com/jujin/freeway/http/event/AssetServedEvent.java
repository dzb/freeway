package com.jujin.freeway.http.event;

/** Published when a static resource is served. Subscribe to upload to CDN, log access, etc. */
public record AssetServedEvent(String relativePath, byte[] content, String contentType) {}
