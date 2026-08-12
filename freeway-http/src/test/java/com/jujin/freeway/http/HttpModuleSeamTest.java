package com.jujin.freeway.http;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.engine.FreewayHttpEngine;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpModuleSeamTest {

    private static final String KEYSTORE_PASSWORD = "changeit";

    /** Test-side fake: supplies attrs on demand while {@code digest()} still
     *  reads the real file, letting the test control the mtime/size half of
     *  the FileStamp without touching the keystore content. */
    private static final class Stamps {
        private final AtomicLong lastModified = new AtomicLong(1);

        BasicFileAttributes attrs(Path path) {
            long time = lastModified.get();
            return new BasicFileAttributes() {
                public FileTime lastModifiedTime() { return FileTime.fromMillis(time); }
                public FileTime lastAccessTime() { return lastModifiedTime(); }
                public FileTime creationTime() { return lastModifiedTime(); }
                public boolean isRegularFile() { return true; }
                public boolean isDirectory() { return false; }
                public boolean isSymbolicLink() { return false; }
                public boolean isOther() { return false; }
                public long size() { return 1; }
                public Object fileKey() { return path; }
            };
        }
    }

    @Test
    void sslReloaderClosesInjectedScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var engine = new FreewayHttpEngine(new JsonCodecDefault(), new CoercerDefault());
        var reloader = reloader(engine, scheduler, Path.of("/tmp/key"), "secret",
            path -> new Stamps().attrs(path));
        reloader.close();
        assertTrue(scheduler.isShutdown());
    }

    @Test
    void sslReloaderKeepsContextWhenStampsAreStable(
            @TempDir Path tempDir) throws Exception {
        var keystore = generateKeyStore(tempDir);
        var initial = SSLContext.getInstance("TLS");
        var engine = engine(initial);
        var stamps = new Stamps();
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var reloader = reloader(engine, scheduler, keystore,
            KEYSTORE_PASSWORD, path -> stamps.attrs(path));

        // start() takes the baseline snapshot; close() stops the scheduler so
        // the manual check() calls below are deterministic.
        reloader.start();
        reloader.close();
        reloader.check();
        reloader.check();

        assertSame(initial, engine.sslContext(),
            "unchanged stamps must not swap the SSL context");
    }

    @Test
    void sslReloaderSwapsContextWhenStampsChange(
            @TempDir Path tempDir) throws Exception {
        var keystore = generateKeyStore(tempDir);
        var initial = SSLContext.getInstance("TLS");
        var engine = engine(initial);
        var stamps = new Stamps();
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var reloader = reloader(engine, scheduler, keystore,
            KEYSTORE_PASSWORD, path -> stamps.attrs(path));

        reloader.start();
        reloader.close();
        reloader.check();
        stamps.lastModified.set(2);
        reloader.check();

        assertNotSame(initial, engine.sslContext(),
            "a changed stamp must trigger a context reload");
    }

    @Test
    void sslReloaderKeepsPreviousContextOnTransientFailure(
            @TempDir Path tempDir) throws Exception {
        var keystore = generateKeyStore(tempDir);
        var initial = SSLContext.getInstance("TLS");
        var engine = engine(initial);
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var reloader = reloader(engine, scheduler, keystore,
            KEYSTORE_PASSWORD, path -> {
                throw new IOException("transient failure");
            });

        reloader.check();

        assertSame(initial, engine.sslContext(),
            "a failed snapshot must keep the previous context");
        reloader.close();
    }

    private static FreewayHttpEngine engine(SSLContext initial) {
        return new FreewayHttpEngine(
            new JsonCodecDefault(), new CoercerDefault(), initial, false, null);
    }

    private static SslReloader reloader(
            FreewayHttpEngine engine, ScheduledThreadPoolExecutor scheduler,
            Path keyStorePath, String password,
            SslReloader.FileStampProvider provider) {
        var settings = new HttpModule.SslSettings(
            true, keyStorePath.toString(), password, "PKCS12",
            false, null, null, "PKCS12", false, null, null,
            null, Duration.ZERO);
        return new SslReloader(engine, settings, scheduler, provider);
    }

    private static Path generateKeyStore(Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", KEYSTORE_PASSWORD,
                "-dname", "CN=seam.example", "-validity", "1")
            .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertTrue(keytool.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                && keytool.exitValue() == 0,
            "keytool should generate a keystore");
        return keystore;
    }
}
