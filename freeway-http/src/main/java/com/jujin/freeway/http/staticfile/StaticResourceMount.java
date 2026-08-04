package com.jujin.freeway.http.staticfile;

import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.PathPattern;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

public final class StaticResourceMount {
    private static final long DEFAULT_CACHE_MAX_AGE_SECONDS = 86_400L;
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L; // 50MB
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final byte[] NOT_FOUND_BODY = "Not Found".getBytes(StandardCharsets.UTF_8);

    private final String mountPath;
    private final ResourceSource source;
    private final long cacheMaxAgeSeconds;
    private final boolean immutable;
    private final boolean fallthrough;

    private StaticResourceMount(
        String mountPath, ResourceSource source,
        long cacheMaxAgeSeconds, boolean immutable,
        boolean fallthrough
    ) {
        this.mountPath = normalizeMount(mountPath);
        this.source = Objects.requireNonNull(source, "source");
        this.cacheMaxAgeSeconds = Math.max(0L, cacheMaxAgeSeconds);
        this.immutable = immutable;
        this.fallthrough = fallthrough;
    }

    public static StaticResourceMount directory(String mountPath, Path root) {
        return new StaticResourceMount(mountPath, new DirectoryResourceSource(root), DEFAULT_CACHE_MAX_AGE_SECONDS, false, false);
    }

    public static StaticResourceMount classpath(String mountPath, String resourceRoot) {
        return new StaticResourceMount(mountPath, new ClasspathResourceSource(resourceRoot), DEFAULT_CACHE_MAX_AGE_SECONDS, false, false);
    }

    public StaticResourceMount cacheMaxAgeSeconds(long cacheMaxAgeSeconds) {
        return new StaticResourceMount(mountPath, source, cacheMaxAgeSeconds, immutable, fallthrough);
    }

    public StaticResourceMount immutable(boolean immutable) {
        return new StaticResourceMount(mountPath, source, cacheMaxAgeSeconds, immutable, fallthrough);
    }

    /**
     * When true, passes the request back to the route chain instead of
     * returning 404 when the file is not found. Similar to nginx's
     * {@code try_files} directive — useful for SPA front-end routing.
     */
    public StaticResourceMount fallthrough(boolean fallthrough) {
        return new StaticResourceMount(mountPath, source, cacheMaxAgeSeconds, immutable, fallthrough);
    }

    public boolean fallthrough() {
        return fallthrough;
    }

    public String mountPath() {
        return mountPath;
    }

    public long cacheMaxAgeSeconds() {
        return cacheMaxAgeSeconds;
    }

    public boolean immutable() {
        return immutable;
    }

    public boolean matches(String method, String path) {
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        if ("/".equals(mountPath)) {
            return path != null && path.startsWith("/");
        }
        return path != null && (path.equals(mountPath) || path.startsWith(mountPath + "/"));
    }

    /**
     * Handles a static resource request.
     *
     * @return true if the request was handled (file sent or 404 returned);
     *         false if the file was not found and {@link #fallthrough} is on, so the request should continue
     */
    public boolean serve(HttpContext ctx) throws IOException {
        String relative = relativePath(ctx.path());
        if (relative == null) {
            return notFound(ctx);
        }
        AssetMeta meta = source.meta(relative);
        if (meta == null) {
            return notFound(ctx);
        }
        applyCacheHeaders(ctx, meta);
        if (isNotModified(ctx, meta)) {
            ctx.status(HttpStatus.NOT_MODIFIED).output(new byte[0]);
            return true;
        }
        if ("HEAD".equalsIgnoreCase(ctx.method())) {
            // No body needed — report the headers (and real size) without
            // reading the file contents.
            ctx.status(HttpStatus.OK);
            ctx.headerSet("Content-Type", contentType(meta.name()));
            ctx.headerSet("X-Content-Type-Options", "nosniff");
            if (meta.size() >= 0) {
                ctx.headerSet("Content-Length", Long.toString(meta.size()));
            }
            ctx.output(new byte[0]);
            return true;
        }
        StaticAsset asset = source.load(relative);
        if (asset == null) {
            return notFound(ctx);
        }
        ctx.status(HttpStatus.OK);
        ctx.headerSet("Content-Type", contentType(asset.meta().name()));
        ctx.headerSet("X-Content-Type-Options", "nosniff");
        ctx.output(asset.bytes());
        return true;
    }

    private boolean notFound(HttpContext ctx) throws IOException {
        if (fallthrough) {
            return false;
        }
        ctx.status(404).headerSet("Content-Type", "text/plain; charset=utf-8").output(NOT_FOUND_BODY);
        return true;
    }

    private String relativePath(String path) {
        String normalized = Strings.blankToNull(path);
        if (normalized == null) {
            return null;
        }
        if ("/".equals(mountPath)) {
            normalized = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        } else if (normalized.equals(mountPath)) {
            normalized = "index.html";
        } else if (normalized.startsWith(mountPath + "/")) {
            normalized = normalized.substring(mountPath.length() + 1);
        } else {
            return null;
        }
        if (normalized.isBlank()) {
            normalized = "index.html";
        }
        try {
            // URLDecoder treats + as space (form encoding); file paths use %2B
            normalized = URLDecoder.decode(
                normalized.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return "index.html";
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || "..".equals(segment) || segment.contains("\\") || segment.contains("\0")) {
                return null;
            }
        }
        return normalized;
    }

    private void applyCacheHeaders(HttpContext ctx, AssetMeta meta) {
        StringBuilder cacheControl = new StringBuilder("public, max-age=").append(cacheMaxAgeSeconds);
        if (immutable) {
            cacheControl.append(", immutable");
        }
        ctx.headerSet("Cache-Control", cacheControl.toString());
        if (meta.lastModifiedMillis() > 0) {
            ctx.headerSet("Last-Modified", httpDate(meta.lastModifiedMillis()));
        }
        ctx.headerSet("ETag", meta.etag());
    }

    private boolean isNotModified(HttpContext ctx, AssetMeta meta) {
        String ifNoneMatch = Strings.blankToNull(ctx.header("If-None-Match").orElse(null));
        if (ifNoneMatch != null) {
            return etagMatches(ifNoneMatch, meta.etag());
        }
        String ifModifiedSince = Strings.blankToNull(ctx.header("If-Modified-Since").orElse(null));
        if (ifModifiedSince == null || meta.lastModifiedMillis() <= 0) {
            return false;
        }
        try {
            Instant requested = ZonedDateTime.parse(ifModifiedSince, HTTP_DATE).toInstant();
            Instant lastModified = Instant.ofEpochMilli(meta.lastModifiedMillis());
            return !lastModified.isAfter(requested);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static boolean etagMatches(String header, String etag) {
        for (String token : header.split(",")) {
            String candidate = token.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if ("*".equals(candidate)) {
                return true;
            }
            if (candidate.startsWith("W/")) {
                candidate = candidate.substring(2).trim();
            }
            if (candidate.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static String httpDate(long millis) {
        return HTTP_DATE.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    private static String contentType(String name) {
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
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
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
        return "application/octet-stream";
    }

    private static String normalizeMount(String mountPath) {
        return PathPattern.normalizePath(mountPath);
    }

    private interface ResourceSource {
        /** Lightweight metadata lookup — does not read file contents. */
        AssetMeta meta(String relative) throws IOException;

        /** Full load: metadata plus contents. */
        StaticAsset load(String relative) throws IOException;
    }

    private record AssetMeta(String name, long size, long lastModifiedMillis, String etag) {}

    private record StaticAsset(AssetMeta meta, byte[] bytes) {
        StaticAsset {
            bytes = bytes.clone();
        }
    }

    private static final class DirectoryResourceSource implements ResourceSource {
        private final Path root;

        private DirectoryResourceSource(Path root) {
            this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }

        @Override
        public AssetMeta meta(String relative) throws IOException {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            Path realCandidate;
            try {
                realCandidate = candidate.toRealPath();
            } catch (IOException e) {
                return null;
            }
            if (!realCandidate.startsWith(root.toRealPath()) || !Files.isRegularFile(realCandidate)) {
                return null;
            }
            long size = Files.size(realCandidate);
            if (size > MAX_FILE_SIZE_BYTES) {
                throw new IOException("File too large: " + candidate.getFileName() + " (" + size + " bytes, max " + MAX_FILE_SIZE_BYTES + ")");
            }
            long lastModified = Files.getLastModifiedTime(realCandidate).toMillis();
            return new AssetMeta(realCandidate.getFileName().toString(),
                size, lastModified, etag(lastModified, size));
        }

        @Override
        public StaticAsset load(String relative) throws IOException {
            AssetMeta meta = meta(relative);
            if (meta == null) {
                return null;
            }
            // Re-verify containment on the load path: the file may have been
            // replaced by a symlink between meta() and here (TOCTOU).
            Path candidate = root.resolve(relative).normalize();
            Path realCandidate;
            try {
                realCandidate = candidate.toRealPath();
            } catch (IOException e) {
                return null;
            }
            if (!realCandidate.startsWith(root.toRealPath())
                    || !Files.isRegularFile(realCandidate)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(realCandidate);
            if (bytes.length > MAX_FILE_SIZE_BYTES) {
                throw new IOException("File too large: " + realCandidate.getFileName()
                    + " (" + bytes.length + " bytes, max " + MAX_FILE_SIZE_BYTES + ")");
            }
            if (bytes.length != meta.size()) {
                // File changed between meta() and load() — refresh metadata so
                // the ETag/Last-Modified headers match the bytes being sent.
                long lastModified = Files.getLastModifiedTime(realCandidate).toMillis();
                meta = new AssetMeta(
                    meta.name(), bytes.length, lastModified, etag(lastModified, bytes.length));
            }
            return new StaticAsset(meta, bytes);
        }
    }

    private static final class ClasspathResourceSource implements ResourceSource {
        private final String root;
        private final ClassLoader loader;

        private ClasspathResourceSource(String resourceRoot) {
            String normalized = Strings.blankToNull(resourceRoot);
            if (normalized == null) {
                normalized = "";
            }
            normalized = normalized.startsWith("/") ? normalized.substring(1) : normalized;
            normalized = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            this.root = normalized;
            this.loader = Thread.currentThread().getContextClassLoader();
        }

        @Override
        public AssetMeta meta(String relative) throws IOException {
            String resourceName = root.isEmpty() ? relative : root + "/" + relative;
            URL url = loader.getResource(resourceName);
            if (url == null) {
                return null;
            }
            URLConnection connection = url.openConnection();
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_FILE_SIZE_BYTES) {
                throw new IOException("Classpath resource too large: " + resourceName + " (" + contentLength + " bytes, max " + MAX_FILE_SIZE_BYTES + ")");
            }
            // -1 means the length is unknown (e.g. some custom classloaders);
            // callers must not report a bogus Content-Length for it.
            long size = contentLength < 0 ? -1 : contentLength;
            long lastModified = connection.getLastModified();
            return new AssetMeta(relative, size, lastModified, etag(lastModified, size));
        }

        @Override
        public StaticAsset load(String relative) throws IOException {
            AssetMeta meta = meta(relative);
            if (meta == null) {
                return null;
            }
            String resourceName = root.isEmpty() ? relative : root + "/" + relative;
            URL url = loader.getResource(resourceName);
            if (url == null) {
                return null;
            }
            try (InputStream in = url.openConnection().getInputStream()) {
                return new StaticAsset(meta, ByteStreams.readBytes(in, MAX_FILE_SIZE_BYTES, resourceName));
            }
        }
    }

    private static String etag(long lastModifiedMillis, long size) {
        return "\"" + lastModifiedMillis + "-" + size + "\"";
    }
}
