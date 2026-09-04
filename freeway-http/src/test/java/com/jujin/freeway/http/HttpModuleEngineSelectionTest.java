package com.jujin.freeway.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS hot reload assembly: the {@code SslReloader} must only be started when
 * the built-in {@code FreewayHttpEngine} is the engine actually serving.
 * An ext engine selected via {@code primary()} (e.g. an Undertow/Jetty
 * adapter) must NOT silently reload a never-started built-in engine.
 */
class HttpModuleEngineSelectionTest {

    private static final String RELOAD_THREAD = "freeway-ssl-reload";
    private AppRuntime app;

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.close();
            app = null;
        }
        awaitNoReloadThread(3000);
        System.clearProperty(HttpConfigKeys.SERVER_HOST);
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(HttpConfigKeys.SSL_ENABLED);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE);
        System.clearProperty(HttpConfigKeys.SSL_HTTP2);
        System.clearProperty(HttpConfigKeys.SSL_RELOAD_INTERVAL);
    }

    @Test
    void selectionProbeIsTrueForTheBuiltinEngine() {
        try (Container container = Freeway.create(new HttpModule())) {
            assertTrue(HttpModule.isBuiltinEngineActive(container),
                "without an ext binding the built-in HttpEngine must be the active engine");
        }
    }

    @Test
    void selectionProbeIsFalseWhenAnExtEngineIsPrimary() {
        try (Container container = Freeway.create(new HttpModule(), new FakeEngineModule())) {
            assertFalse(HttpModule.isBuiltinEngineActive(container),
                "a primary ext engine must not be mistaken for the built-in engine");
            assertNotNull(container.get(HttpEngine.class),
                "the primary ext engine must be the resolved engine");
        }
    }

    @Test
    void builtinEngineAssemblesTheSslReloader(@TempDir Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("server.p12");
        generateKeyStoreTo(keystore);

        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(freePort()));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "false");
        System.setProperty(HttpConfigKeys.SSL_RELOAD_INTERVAL, "200ms");

        app = FreewayApp.run(); // HttpModule is ServiceLoader-discovered

        assertTrue(awaitReloadThread(3000),
            "the built-in engine serving HTTPS must run the keystore reloader");
    }

    @Test
    void extPrimaryEngineSkipsTheSslReloader(@TempDir Path tempDir) throws Exception {
        // SSL + reload interval are configured, but the keystore path points
        // at a file that does not exist: with the reloader correctly skipped,
        // nothing ever reads it (no built-in engine is realized). A regression
        // to the unconditional assembly would fail startup right here.
        Path missing = tempDir.resolve("does-not-exist.p12");
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(freePort()));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, missing.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "false");
        System.setProperty(HttpConfigKeys.SSL_RELOAD_INTERVAL, "200ms");

        app = FreewayApp.run(new FakeEngineModule());

        assertTrue(app.get(WebServer.class).isRunning(),
            "the ext engine must serve the web server");
        // Grace period well past one reload interval: the reloader must not
        // exist at all (its scheduled check starts after the first interval,
        // but the scheduler thread is created immediately on start()).
        Thread.sleep(600);
        assertFalse(awaitReloadThread(100),
            "no SslReloader may be assembled when an ext engine is primary");
    }

    /** An ext engine module stand-in: primary HttpEngine binding under a
     *  distinct id, exactly like the Undertow/Jetty adapters. */
    static final class FakeEngineModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(HttpEngine.class)
                .to(container -> new FakeEngine())
                .id("fake").primary();
        }
    }

    private static final class FakeEngine implements HttpEngine {
        @Override
        public HttpServerHandle start(HttpServerConfig config, ExchangeHandler handler) {
            return new HttpServerHandle() {
                @Override
                public String host() {
                    return "127.0.0.1";
                }

                @Override
                public int port() {
                    return 1;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static boolean reloadThreadExists() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(t -> RELOAD_THREAD.equals(t.getName()));
    }

    private static boolean awaitReloadThread(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (reloadThreadExists()) {
                return true;
            }
            Thread.sleep(25);
        }
        return reloadThreadExists();
    }

    private static void awaitNoReloadThread(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && reloadThreadExists()) {
            Thread.sleep(25);
        }
    }

    private static void generateKeyStoreTo(Path keystore) throws Exception {
        Process keytool = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-dname", "CN=localhost", "-validity", "1")
            .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS) && keytool.exitValue() == 0,
            "keytool should generate a keystore");
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
