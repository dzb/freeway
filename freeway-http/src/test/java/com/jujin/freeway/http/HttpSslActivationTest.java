package com.jujin.freeway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.route.Route;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import javax.net.ssl.SNIHostName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Presence-driven HTTPS activation: a configured keystore alone is an HTTPS
 * server; an explicit {@code ssl.enabled} wins ({@code false} = kill switch
 * suppressing the keystore); explicit {@code true} without a keystore and
 * garbage values fail naming the key.
 */
class HttpSslActivationTest {

    private AppRuntime app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
            app = null;
        }
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(HttpConfigKeys.SSL_ENABLED);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE);
    }

    @Test
    void keystorePresenceActivatesHttps(@TempDir Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("server.p12");
        generateKeyStoreTo(keystore);
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class)
                .add(Route.get("/", ctx -> ctx.send(200, "ok"))));

        assertTrue(app.get(com.jujin.freeway.http.SslSettings.class).enabled(),
            "a configured keystore must activate HTTPS without ssl.enabled");
        assertTrue(app.get(com.jujin.freeway.http.HttpServer.class).secure(),
            "the server must report the transport it was configured with — the cloud"
                + " registry endpoint and mesh origin derive their scheme from it");
        assertEquals("CN=localhost",
            cn(httpsConnect(port, null)),
            "the presence-activated engine must speak TLS");
    }

    @Test
    void explicitFalseSuppressesConfiguredKeystore(@TempDir Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("server.p12");
        generateKeyStoreTo(keystore);
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "false");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class)
                .add(Route.get("/", ctx -> ctx.send(200, "ok"))));

        assertFalse(app.get(com.jujin.freeway.http.SslSettings.class).enabled(),
            "the kill switch must suppress the configured keystore");
        assertFalse(app.get(com.jujin.freeway.http.HttpServer.class).secure(),
            "the kill switch must reach the server's reported transport too");
        // Plain HTTP on the same port: the TLS attempt would fail the read.
        assertEquals(200, plainGet(port));
    }

    @Test
    void explicitTrueWithoutKeystoreFailsNamingTheKey() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(freePortQuiet()));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            FreewayApp.run(new String[0], binder ->
                binder.contribute(Route.class)
                    .add(Route.get("/", ctx -> ctx.send(200, "ok")))));
        assertTrue(rootMessage(failure).contains("ssl.key-store"),
            "the failure must name the missing key: " + rootMessage(failure));
    }

    @Test
    void invalidEnabledValueFailsNamingTheKey() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(freePortQuiet()));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "yolo");

        Exception failure = assertThrows(Exception.class, () ->
            FreewayApp.run(new String[0], binder ->
                binder.contribute(Route.class)
                    .add(Route.get("/", ctx -> ctx.send(200, "ok")))));
        assertTrue(rootMessage(failure).contains("ssl.enabled"),
            "the failure must name the key: " + rootMessage(failure));
        assertTrue(rootMessage(failure).contains("yolo"),
            "the failure must show the offending value: " + rootMessage(failure));
    }

    // ==================== harness ====================

    private static int plainGet(int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode();
    }

    private static X509Certificate httpsConnect(int port, String sni) throws Exception {
        SSLContext trustAll = trustAllSslContext();
        try (SSLSocket socket = (SSLSocket) trustAll.getSocketFactory()
                .createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            SSLParameters params = socket.getSSLParameters();
            if (sni != null) {
                params.setServerNames(List.of(new SNIHostName(sni)));
            }
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static String cn(X509Certificate cert) {
        return cert.getSubjectX500Principal().getName();
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{
            new X509TrustManager() {
                @Override public void checkClientTrusted(
                    X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(
                    X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        }, null);
        return trustAll;
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

    private static int freePortQuiet() {
        try {
            return freePort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder seen = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            seen.append(t.getMessage()).append(" | ");
        }
        return seen.toString();
    }
}
