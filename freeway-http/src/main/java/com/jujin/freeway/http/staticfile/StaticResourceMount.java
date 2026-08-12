package com.jujin.freeway.http.staticfile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.PathPattern;

public final class StaticResourceMount {
    private static final Logger LOG = LoggerFactory.getLogger(StaticResourceMount.class);
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

        ByteRange range = ifRangeAllows(ctx, meta)
            ? parseRange(ctx.header("Range").orElse(null), meta.size())
            : null;

        if (range != null && !range.satisfiable()) {
            ctx.status(416);
            ctx.setHeader("Content-Range", "bytes */" + meta.size());
            ctx.output(new byte[0]);
            return true;
        }

        if (range != null) {
            long length = range.end() - range.start() + 1;
            ctx.status(206);
            ctx.setHeader("Content-Range",
                "bytes " + range.start() + "-" + range.end() + "/" + meta.size());
            if (!serveBytes(ctx, meta, relative, range.start(), length)) {
                return notFound(ctx);
            }
            return true;
        }

        if (!serveBytes(ctx, meta, relative, 0, meta.size())) {
            return notFound(ctx);
        }
        return true;
    }

    /**
     * Serves the asset through the fastest available path: secure sendfile
     * channel, verified file path, body stream, or fully loaded bytes.
     *
     * <p>{@code start} is the byte offset and {@code length} the byte count;
     * a negative length means unknown (classpath sources), streamed chunked.
     * The file path is re-resolved at use time because the asset may have
     * been replaced by a symlink between {@code meta()} and here (TOCTOU);
     * {@code resolve()} re-checks containment on every access.</p>
     *
     * @return true when handled; false when the asset vanished and the
     *         caller should fall through to {@link #notFound}
     */
    private boolean serveBytes(HttpContext ctx, AssetMeta meta, String relative,
                               long start, long length) throws IOException {
        ctx.setHeader("Content-Type", contentType(meta.name()));
        ctx.setHeader("X-Content-Type-Options", "nosniff");
        if ("HEAD".equalsIgnoreCase(ctx.method())) {
            // No body needed — report the headers (and real size) without
            // reading the file contents.
            if (length >= 0) {
                ctx.setHeader("Content-Length", Long.toString(length));
            }
            ctx.output(new byte[0]);
            return true;
        }
        // sendfile fast path: real files on plain HTTP get transferred
        // straight from the filesystem cache to the socket.
        if (length >= 0 && trySendfile(ctx, source, relative, start, length)) {
            return true;
        }
        Path file = source.file(relative);
        if (file != null) {
            ctx.outputFile(file, start, Math.max(0, length));
            return true;
        }
        // Stream when the source can open a body stream directly (disk
        // files) — avoids materializing the whole file in memory; classpath
        // sources fall back to their loaded bytes.
        InputStream body = source.open(relative);
        if (body != null) {
            try {
                if (length >= 0) {
                    body.skipNBytes(start);
                    ctx.output(new BoundedInputStream(body, length), length);
                } else {
                    ctx.output(body, -1);
                }
                return true;
            } finally {
                body.close();
            }
        }
        StaticAsset asset = source.load(relative);
        if (asset == null) {
            return false;
        }
        if (length >= 0) {
            ctx.output(Arrays.copyOfRange(asset.bytes(), (int) start,
                (int) (start + length)));
        } else {
            ctx.output(asset.bytes());
        }
        return true;
    }

    /**
     * Opens the asset through the secure channel path and hands the channel
     * to the context. Ownership: the context closes the channel exactly
     * once, including failure paths (see
     * {@link HttpContext#outputFile(FileChannel, long, long)});
     * when the context cannot consume channels
     * ({@link UnsupportedOperationException}) the caller closes it before
     * falling back to the file/stream paths.
     */
    private static boolean trySendfile(HttpContext ctx, ResourceSource source,
                                       String relative, long offset, long length)
            throws IOException {
        FileChannel channel = source.openChannel(relative);
        if (channel == null) {
            return false;
        }
        try {
            ctx.outputFile(channel, offset, length);
            return true;
        } catch (UnsupportedOperationException e) {
            channel.close();
            return false;
        }
    }

    private boolean notFound(HttpContext ctx) throws IOException {
        if (fallthrough) {
            return false;
        }
        ctx.status(404).setHeader("Content-Type", "text/plain; charset=utf-8").output(NOT_FOUND_BODY);
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
        ctx.setHeader("Cache-Control", cacheControl.toString());
        if (meta.lastModifiedMillis() > 0) {
            ctx.setHeader("Last-Modified", httpDate(meta.lastModifiedMillis()));
        }
        ctx.setHeader("ETag", meta.etag());
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
            Instant lastModified = Instant.ofEpochMilli(meta.lastModifiedMillis() / 1000 * 1000);
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

    /** A parsed single-range request; {@code satisfiable == false} means the
     *  range cannot be satisfied (416). */
    private record ByteRange(long start, long end, boolean satisfiable) {
        static ByteRange unsatisfiable() {
            return new ByteRange(-1, -1, false);
        }
    }

    /** RFC 7233 §2.3: only single byte ranges are supported; unsupported or
     *  malformed range headers are ignored (full 200), unsatisfiable ranges
     *  are flagged for a 416 response. */
    private static ByteRange parseRange(String header, long size) {
        if (header == null || size < 0) {
            return null;
        }
        String spec = header.trim();
        if (!spec.startsWith("bytes=")) {
            return null;
        }
        String part = spec.substring(6).trim();
        if (part.contains(",")) {
            return null; // multi-range requests are served as the full body
        }
        int dash = part.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startStr = part.substring(0, dash).trim();
        String endStr = part.substring(dash + 1).trim();
        long start;
        long end;
        try {
            if (startStr.isEmpty()) {
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) return ByteRange.unsatisfiable();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(startStr);
                if (start < 0 || start >= size) {
                    return ByteRange.unsatisfiable();
                }
                end = endStr.isEmpty() ? size - 1 : Long.parseLong(endStr);
                if (end < start) return ByteRange.unsatisfiable();
                end = Math.min(end, size - 1);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new ByteRange(start, end, true);
    }

    /** RFC 7233 §3.2: If-Range matching — a strong ETag or a Last-Modified
     *  timestamp that matches the current representation allows the range. */
    private static boolean ifRangeAllows(HttpContext ctx, AssetMeta meta) {
        String ifRange = Strings.blankToNull(ctx.header("If-Range").orElse(null));
        if (ifRange == null) return true;
        if (ifRange.startsWith("\"")) {
            return ifRange.equals(meta.etag());
        }
        if (ifRange.startsWith("W/")) {
            return false;
        }
        try {
            Instant requested = ZonedDateTime.parse(ifRange, HTTP_DATE).toInstant();
            Instant lastModified = Instant.ofEpochMilli(meta.lastModifiedMillis());
            return !lastModified.isAfter(requested);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Reads at most {@code length} bytes from the delegate, then EOF. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
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

        /** Real file backing the resource for the sendfile path; null if the
         *  source is not a plain file (classpath, archives, etc.). */
        default Path file(String relative) throws IOException {
            return null;
        }

        /** Full load: metadata plus contents. */
        StaticAsset load(String relative) throws IOException;

        /** Opens a body stream for streaming responses; null to use load(). */
        default InputStream open(String relative) throws IOException {
            return null;
        }

        default FileChannel openChannel(String relative) throws IOException {
            return null;
        }
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

        @Override
        public InputStream open(String relative) throws IOException {
            Path real = resolve(relative);
            return real == null ? null : Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public FileChannel openChannel(String relative) throws IOException {
            try {
                Path rootPath = root.toRealPath();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootPath)) {
                    if (!(stream instanceof SecureDirectoryStream<Path> secureRoot)) {
                        // Degraded platform: no symlink-race-free directory
                        // walks. Fall back to a containment-verified real-path
                        // open; the resolution-vs-open race remains, so this
                        // is weaker than SecureDirectoryStream.
                        LOG.debug(
                            "SecureDirectoryStream unavailable for mount {} — "
                                + "falling back to verified real-path open",
                            root);
                        return fallbackChannel(relative);
                    }
                    SecureDirectoryStream<Path> current = secureRoot;
                    try {
                        String[] parts = relative.split("/");
                        for (int i = 0; i < parts.length - 1; i++) {
                            var next = current.newDirectoryStream(
                                Path.of(parts[i]), LinkOption.NOFOLLOW_LINKS);
                            if (current != secureRoot) current.close();
                            current = next;
                        }
                        Set<OpenOption> options = new HashSet<>();
                        options.add(StandardOpenOption.READ);
                        var opened = current.newByteChannel(
                            Path.of(parts[parts.length - 1]), options);
                        if (!(opened instanceof FileChannel channel)) {
                            opened.close();
                            return null;
                        }
                        return channel;
                    } finally {
                        if (current != secureRoot) current.close();
                    }
                }
            } catch (FileSystemException e) {
                // The secure walk hit a non-directory segment or a vanished
                // entry — fall back to the verified path/stream/load routes.
                LOG.debug(
                    "SecureDirectoryStream open failed for {} under {}: {}",
                    relative, root, e.getMessage());
                return fallbackChannel(relative);
            }
        }

        /**
         * Containment-verified fallback open. Resolves the real path inside
         * the mount root and opens it read-only; unlike
         * {@link SecureDirectoryStream} this cannot prevent a race between
         * resolution and open, so it is only used on degraded platforms or
         * when the secure walk fails.
         */
        private FileChannel fallbackChannel(String relative) throws IOException {
            Path real = resolve(relative);
            return real == null
                ? null
                : FileChannel.open(real, StandardOpenOption.READ);
        }

        @Override
        public Path file(String relative) throws IOException {
            return resolve(relative);
        }

        /** Re-verifies containment on every access path (TOCTOU): the file may
         *  have been replaced by a symlink between meta() and here. */
        private Path resolve(String relative) throws IOException {
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
            if (Files.size(realCandidate) > MAX_FILE_SIZE_BYTES) {
                throw new IOException("File too large: " + realCandidate.getFileName()
                    + " (" + Files.size(realCandidate) + " bytes, max "
                    + MAX_FILE_SIZE_BYTES + ")");
            }
            return realCandidate;
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
