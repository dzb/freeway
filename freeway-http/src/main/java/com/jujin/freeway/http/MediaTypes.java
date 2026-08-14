package com.jujin.freeway.http;

import java.util.Locale;

/**
 * Shared media-type constants and predicates for the HTTP SPI. Kept in the
 * root package so built-in and external engine adapters use one source for
 * content-type classification instead of duplicating string checks.
 */
public final class MediaTypes {

    public static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";
    public static final String JSON_UTF8 = "application/json; charset=utf-8";
    public static final String EVENT_STREAM_UTF8 =
        "text/event-stream; charset=utf-8";
    public static final String OCTET_STREAM = "application/octet-stream";

    private MediaTypes() {}

    /**
     * True when the Content-Type identifies JSON: the exact
     * {@code application/json} media type or a structured syntax suffix
     * ({@code application/*+json}, e.g. {@code application/vnd.api+json}),
     * ignoring any parameters. Case-insensitive, null-safe.
     */
    public static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String mediaType = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return "application/json".equals(mediaType)
            || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /** True when the Content-Type identifies multipart/form-data. */
    public static boolean isMultipartFormData(String contentType) {
        return contentType != null
            && contentType.toLowerCase(Locale.ROOT)
                .contains("multipart/form-data");
    }

    /** True when a response Content-Type is eligible for gzip compression. */
    public static boolean isCompressibleContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
            || lower.startsWith("application/json")
            || lower.startsWith("application/javascript")
            || lower.startsWith("application/xml")
            || lower.startsWith("application/xhtml+xml")
            || lower.startsWith("image/svg+xml");
    }
}
