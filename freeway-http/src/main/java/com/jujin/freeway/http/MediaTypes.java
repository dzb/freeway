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

    /** True when the Content-Type identifies multipart/form-data — the media
     *  type is compared, so a parameter that merely mentions the string
     *  ({@code text/plain; note=multipart/form-data}) is not an upload. */
    public static boolean isMultipartFormData(String contentType) {
        if (contentType == null) return false;
        return "multipart/form-data".equals(
            contentType.toLowerCase(Locale.ROOT).split(";")[0].trim());
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

    /**
     * The Content-Type for a file name, by extension — the one extension
     * table, read by the static-file server and by any adapter that maps
     * names to media types, so an added extension has a single place to
     * land. Unknown names get {@link #OCTET_STREAM}. Text types carry
     * {@code charset=utf-8}.
     */
    public static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return JSON_UTF8;
        }
        if (lower.endsWith(".txt")) {
            return TEXT_PLAIN_UTF8;
        }
        if (lower.endsWith(".xml")) {
            return "application/xml; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return OCTET_STREAM;
    }
}
