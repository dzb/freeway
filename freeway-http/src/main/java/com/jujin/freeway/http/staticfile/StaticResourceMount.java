package com.jujin.freeway.http.staticfile;

import com.jujin.freeway.commons.io.InputStreams;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.PathPattern;

public final class StaticResourceMount {
    private static final long DEFAULT_CACHE_MAX_AGE_SECONDS = 86_400L;
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L; // 50MB
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

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
     * 文件不存在时是否把请求交还给路由链（而非返回 404）。
     * 启用后行为类似 nginx 的 {@code try_files}，适合 SPA 前端路由场景。
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
     * 处理静态资源请求。
     *
     * @return true 表示请求已被处理（文件已发送或 404 已返回）；
     *         false 表示文件不存在且 {@link #fallthrough} 启用，请求应交还给路由链
     */
    public boolean serve(HttpContext ctx) throws IOException {
        String relative = relativePath(ctx.path());
        if (relative == null) {
            return notFound(ctx);
        }
        StaticAsset asset = source.load(relative);
        if (asset == null) {
            return notFound(ctx);
        }
        applyCacheHeaders(ctx, asset);
        if (isNotModified(ctx, asset)) {
            ctx.status(304).output(new byte[0]);
            return true;
        }
        ctx.status(200);
        ctx.headerSet("Content-Type", contentType(asset.name()));
        ctx.headerSet("X-Content-Type-Options", "nosniff");
        ctx.output(asset.bytes());
        return true;
    }

    private boolean notFound(HttpContext ctx) throws IOException {
        if (fallthrough) {
            return false;
        }
        ctx.send(404, "Not Found");
        return true;
    }

    private String relativePath(String path) {
        String normalized = HttpContext.blankToNull(path);
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
            normalized = URLDecoder.decode(normalized, StandardCharsets.UTF_8);
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

    private void applyCacheHeaders(HttpContext ctx, StaticAsset asset) {
        StringBuilder cacheControl = new StringBuilder("public, max-age=").append(cacheMaxAgeSeconds);
        if (immutable) {
            cacheControl.append(", immutable");
        }
        ctx.headerSet("Cache-Control", cacheControl.toString());
        if (asset.lastModifiedMillis() > 0) {
            ctx.headerSet("Last-Modified", httpDate(asset.lastModifiedMillis()));
        }
        ctx.headerSet("ETag", asset.etag());
    }

    private boolean isNotModified(HttpContext ctx, StaticAsset asset) {
        String ifNoneMatch = HttpContext.blankToNull(ctx.header("If-None-Match"));
        if (ifNoneMatch != null) {
            return etagMatches(ifNoneMatch, asset.etag());
        }
        String ifModifiedSince = HttpContext.blankToNull(ctx.header("If-Modified-Since"));
        if (ifModifiedSince == null || asset.lastModifiedMillis() <= 0) {
            return false;
        }
        try {
            Instant requested = ZonedDateTime.parse(ifModifiedSince, HTTP_DATE).toInstant();
            Instant lastModified = Instant.ofEpochMilli(asset.lastModifiedMillis());
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
        StaticAsset load(String relative) throws IOException;
    }

    private record StaticAsset(String name, byte[] bytes, long lastModifiedMillis, String etag) {
        StaticAsset(String name, byte[] bytes, long lastModifiedMillis) {
            this(name, bytes, lastModifiedMillis, computeEtag(bytes));
        }
    }

    private static final class DirectoryResourceSource implements ResourceSource {
        private final Path root;

        private DirectoryResourceSource(Path root) {
            this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }

        @Override
        public StaticAsset load(String relative) throws IOException {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            Path realRoot;
            Path realCandidate;
            try {
                realRoot = root.toRealPath();
                realCandidate = candidate.toRealPath();
            } catch (IOException e) {
                return null;
            }
            if (!realCandidate.startsWith(realRoot) || !Files.isRegularFile(realCandidate)) {
                return null;
            }
            long size = Files.size(realCandidate);
            if (size > MAX_FILE_SIZE_BYTES) {
                throw new IOException("File too large: " + candidate.getFileName() + " (" + size + " bytes, max " + MAX_FILE_SIZE_BYTES + ")");
            }
            return new StaticAsset(candidate.getFileName().toString(), Files.readAllBytes(realCandidate), Files.getLastModifiedTime(realCandidate).toMillis());
        }
    }

    private static final class ClasspathResourceSource implements ResourceSource {
        private final String root;
        private final ClassLoader loader;

        private ClasspathResourceSource(String resourceRoot) {
            String normalized = HttpContext.blankToNull(resourceRoot);
            if (normalized == null) {
                normalized = "";
            }
            normalized = normalized.startsWith("/") ? normalized.substring(1) : normalized;
            normalized = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            this.root = normalized;
            this.loader = Thread.currentThread().getContextClassLoader();
        }

        @Override
        public StaticAsset load(String relative) throws IOException {
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
            try (InputStream in = connection.getInputStream()) {
                return new StaticAsset(relative, InputStreams.readBytes(in, MAX_FILE_SIZE_BYTES, resourceName), connection.getLastModified());
            }
        }
    }

    private static String computeEtag(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return "\"sha256-" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash) + "\"";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
