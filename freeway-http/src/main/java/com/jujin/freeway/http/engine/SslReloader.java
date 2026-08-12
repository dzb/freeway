package com.jujin.freeway.http.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Polls keystore mtime/size/digest and swaps a freshly built SSLContext into
 * the engine when certificate material changes. The SSL context builder is
 * injected so this class stays inside the engine and never reaches back into
 * the IoC assembly layer. The scheduler/stamp-injecting constructor is a test
 * seam for package-local tests; production uses the default constructor.
 * Public only so the IoC assembly layer ({@code HttpModule}) can construct
 * it — not part of the application API.
 */
public final class SslReloader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SslReloader.class);

    private final FreewayHttpEngine engine;
    private final Path keyStorePath;
    private final Path trustStorePath; // nullable
    private final Path sniDirectory;   // nullable
    private final Duration reloadInterval;
    private final Supplier<SSLContext> contextBuilder;
    private final ScheduledExecutorService scheduler;
    private final FileStampProvider fileStampProvider;
    private volatile Map<Path, FileStamp> snapshot;

    public SslReloader(FreewayHttpEngine engine, Path keyStorePath,
                       Path trustStorePath, Path sniDirectory,
                       Duration reloadInterval,
                       Supplier<SSLContext> contextBuilder) {
        this(engine, keyStorePath, trustStorePath, sniDirectory,
            reloadInterval, contextBuilder,
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "freeway-ssl-reload");
                t.setDaemon(true);
                return t;
            }), path -> Files.readAttributes(path, BasicFileAttributes.class));
    }

    SslReloader(FreewayHttpEngine engine, Path keyStorePath,
                Path trustStorePath, Path sniDirectory,
                Duration reloadInterval, Supplier<SSLContext> contextBuilder,
                ScheduledExecutorService scheduler,
                FileStampProvider fileStampProvider) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.keyStorePath = Objects.requireNonNull(keyStorePath, "keyStorePath");
        this.trustStorePath = trustStorePath;
        this.sniDirectory = sniDirectory;
        this.reloadInterval = reloadInterval;
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.scheduler = scheduler;
        this.fileStampProvider = fileStampProvider;
    }

    public void start() {
        try {
            snapshot = snapshot();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot snapshot keystore files for reload", e);
        }
        long intervalMillis = Math.max(reloadInterval.toMillis(), 100);
        scheduler.scheduleWithFixedDelay(
            this::check, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Package-level seam: invoked directly by package-local tests. */
    void check() {
        try {
            Map<Path, FileStamp> current = snapshot();
            if (!current.equals(snapshot)) {
                engine.reload(contextBuilder.get());
                snapshot = current;
                LOG.info("Reloaded HTTPS certificate material ({} keystore file(s))",
                    current.size());
            }
        } catch (Exception e) {
            LOG.error("HTTPS certificate reload failed — keeping previous context", e);
        }
    }

    private Map<Path, FileStamp> snapshot() throws IOException {
        Map<Path, FileStamp> files = new LinkedHashMap<>();
        files.put(keyStorePath, stamp(keyStorePath));
        if (trustStorePath != null) {
            files.put(trustStorePath, stamp(trustStorePath));
        }
        if (sniDirectory != null) {
            try (var stream = Files.list(sniDirectory)) {
                for (Path p : stream
                        .filter(SslReloader::isKeystoreFile).sorted().toList()) {
                    files.put(p, stamp(p));
                }
            }
        }
        return files;
    }

    private static boolean isKeystoreFile(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks");
    }

    private FileStamp stamp(Path path) throws IOException {
        BasicFileAttributes attrs = fileStampProvider.stamp(path);
        return new FileStamp(
            attrs.lastModifiedTime().toMillis(), attrs.size(), digest(path));
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n > 0) digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    record FileStamp(long lastModified, long size, String digest) {}

    /** Internal seam: abstracts file-stamp reads for {@link SslReloader}
     *  tests. Not part of the public API. */
    @FunctionalInterface
    interface FileStampProvider {
        BasicFileAttributes stamp(Path path) throws IOException;
    }
}
