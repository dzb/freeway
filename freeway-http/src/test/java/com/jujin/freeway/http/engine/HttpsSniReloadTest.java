package com.jujin.freeway.http.engine;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.route.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpsSniReloadTest {

    private AppRuntime app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
            app = null;
        }
        System.clearProperty(HttpConfigKeys.SERVER_HOST);
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(HttpConfigKeys.SSL_ENABLED);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE);
        System.clearProperty(HttpConfigKeys.SSL_HTTP2);
        System.clearProperty(HttpConfigKeys.SSL_SNI_DIRECTORY);
        System.clearProperty(HttpConfigKeys.SSL_RELOAD_INTERVAL);
    }

    @Test
    void sniDirectorySelectsCertificateByHost(@TempDir Path tempDir) throws Exception {
        Path certs = Files.createDirectory(tempDir.resolve("certs"));
        Path localhost = generateKeyStore(certs, "localhost.p12", "CN=localhost");
        generateKeyStore(certs, "alt.example.p12", "CN=alt.example");

        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, localhost.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "false");
        System.setProperty(HttpConfigKeys.SSL_SNI_DIRECTORY, certs.toString());

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.get("/", ctx -> ctx.send(200, "ok"))));

        assertEquals("CN=alt.example", cn(connect(port, "alt.example")),
            "SNI alt.example must select the alt.example certificate");
        assertEquals("CN=localhost", cn(connect(port, "localhost")),
            "SNI localhost must select the localhost certificate");
        assertEquals("CN=localhost", cn(connect(port, null)),
            "missing SNI must fall back to the default certificate");
    }

    @Test
    void certificateReloadSwapsContextForNewConnections(@TempDir Path tempDir)
            throws Exception {
        Path keystore = tempDir.resolve("server.p12");
        generateKeyStoreTo(keystore, "CN=old.example");

        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "false");
        System.setProperty(HttpConfigKeys.SSL_RELOAD_INTERVAL, "200ms");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.get("/", ctx -> ctx.send(200, "ok"))));

        assertEquals("CN=old.example", cn(connect(port, null)),
            "initial connections must present the original certificate");

        Files.deleteIfExists(keystore);
        generateKeyStoreTo(keystore, "CN=new.example");

        long deadline = System.currentTimeMillis() + 8000;
        String presented = "CN=old.example";
        while (System.currentTimeMillis() < deadline) {
            try {
                presented = cn(connect(port, null));
                if ("CN=new.example".equals(presented)) break;
            } catch (Exception ignored) {
                // reload in progress — retry
            }
            Thread.sleep(200);
        }
        assertEquals("CN=new.example", presented,
            "new connections must present the reloaded certificate");
    }

    private static X509Certificate connect(int port, String sni) throws Exception {
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

    private static Path generateKeyStore(Path dir, String fileName, String dname)
            throws Exception {
        Path keystore = dir.resolve(fileName);
        generateKeyStoreTo(keystore, dname);
        return keystore;
    }

    private static void generateKeyStoreTo(Path keystore, String dname) throws Exception {
        Process keytool = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-dname", dname, "-validity", "1")
            .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS) && keytool.exitValue() == 0,
            "keytool should generate a keystore for " + dname);
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        }, new SecureRandom());
        return trustAll;
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
