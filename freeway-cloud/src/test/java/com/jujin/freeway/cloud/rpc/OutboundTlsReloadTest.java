package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The outbound rotation end to end, with real handshakes: the server presents
 * certificate B, the client initially trusts only A, and swapping the
 * truststore makes the <em>same</em> client context accept B on the next
 * handshake.
 *
 * <p>The assertion that keeps this honest is the negative one: with reload
 * disabled the swapped truststore changes nothing, because the JDK client
 * captured its context at construction. If a future refactor rebuilt the client
 * instead of delegating, both halves would still pass — but rebuilding is what
 * this test's shape forbids (it holds one context across the swap), so the
 * positive assertion alone would prove the delegation happened.</p>
 */
class OutboundTlsReloadTest {

    private static final String PASSWORD = "changeit";

    private SSLServerSocket server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private static Path keytool(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor() == 0, "keytool failed: " + String.join(" ", command));
        return dir;
    }

    private static Path selfSigned(Path dir, String name, String dname) throws Exception {
        keytool(dir, "-genkeypair", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", dname, "-validity", "365", "-alias", "tls", "-storetype", "PKCS12",
            "-keystore", dir.resolve(name).toString(), "-storepass", PASSWORD);
        return dir.resolve(name);
    }

    /** A truststore holding exactly the given store's certificate. */
    private static Path trustOnly(Path dir, Path sourceStore, String name) throws Exception {
        Path cert = dir.resolve(name + ".crt");
        keytool(dir, "-exportcert", "-rfc", "-alias", "tls",
            "-keystore", sourceStore.toString(), "-storepass", PASSWORD,
            "-file", cert.toString());
        Path trust = dir.resolve(name);
        keytool(dir, "-importcert", "-noprompt", "-alias", "peer", "-storetype", "PKCS12",
            "-keystore", trust.toString(), "-storepass", PASSWORD, "-file", cert.toString());
        return trust;
    }

    private static SSLContext serverContext(Path keyStore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    /** Starts a TLS listener presenting {@code serverStore}; returns its port. */
    private int serve(Path serverStore) throws Exception {
        server = (SSLServerSocket) serverContext(serverStore).getServerSocketFactory()
            .createServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                SSLSocket socket = null;
                try {
                    socket = (SSLSocket) server.accept();
                    socket.setSoTimeout(2_000);
                    socket.startHandshake();
                } catch (IOException refused) {
                    // A client that does not trust this certificate aborts the
                    // handshake; the listener must keep accepting, or the next
                    // attempt would hang with nothing to answer it.
                    if (server.isClosed()) {
                        return;
                    }
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                            // already gone
                        }
                    }
                }
            }
        });
        return server.getLocalPort();
    }

    /** True when a handshake completed against the peer. */
    private static boolean handshake(SSLContext client, int port) {
        try {
            SSLSocket socket = (SSLSocket) client.getSocketFactory()
                .createSocket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(2_000); // a listener that vanished must fail, not hang
            socket.startHandshake();
            socket.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void a_swapped_truststore_is_honoured_without_rebuilding_the_client(@TempDir Path dir)
            throws Exception {
        Path serverStore = selfSigned(dir, "server.p12", "CN=server");
        Path otherStore = selfSigned(dir, "other.p12", "CN=other");
        Path trustStore = trustOnly(dir, otherStore, "trust.p12"); // trusts the WRONG peer
        int port = serve(serverStore);

        TransportSecurityDefault transport = TransportSecurityDefault.fromKeyStore(
            serverStore, PASSWORD, trustStore, PASSWORD, Duration.ofMillis(50));
        SSLContext client = transport.sslContext(); // held across the swap on purpose

        assertThrows(Exception.class, () -> {
            if (!handshake(client, port)) {
                throw new IOException("peer refused");
            }
        }, "a truststore without the server's certificate must refuse the handshake");

        // Rotate the truststore in place: now it trusts the server. The client
        // context is the SAME object — only its delegates were refreshed.
        Path replacement = trustOnly(dir, serverStore, "trust-new.p12");
        Thread.sleep(60);
        Files.move(replacement, trustStore, StandardCopyOption.REPLACE_EXISTING);

        assertTrue(handshake(client, port),
            "the next handshake must use the rotated trust anchors");
    }

    @Test
    void with_reload_disabled_the_swapped_truststore_changes_nothing(@TempDir Path dir)
            throws Exception {
        Path serverStore = selfSigned(dir, "server.p12", "CN=server");
        Path otherStore = selfSigned(dir, "other.p12", "CN=other");
        Path trustStore = trustOnly(dir, otherStore, "trust.p12");
        int port = serve(serverStore);

        TransportSecurityDefault transport = TransportSecurityDefault.fromKeyStore(
            serverStore, PASSWORD, trustStore, PASSWORD, Duration.ZERO);
        SSLContext client = transport.sslContext();

        Path replacement = trustOnly(dir, serverStore, "trust-new.p12");
        Thread.sleep(60);
        Files.move(replacement, trustStore, StandardCopyOption.REPLACE_EXISTING);

        assertTrue(!handshake(client, port),
            "without reload the resolved-once context keeps the old anchors — "
                + "this is the behaviour the feature exists to replace");
    }
}
