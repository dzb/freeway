package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * The mesh dials with the application's outbound transport security: a
 * {@code wss://} peer must present the same client identity and trust the same
 * authorities as an RPC call. Without this the mesh was the outbound path that
 * silently ignored the configuration.
 */
class PeerConnectorTlsTest {

    @Test
    void theDialerUsesTheConfiguredSslContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);

        HttpClient client = PeerConnector.newClient(Duration.ofSeconds(1), context);

        assertSame(context, client.sslContext(),
            "the mesh must dial with the configured context, not the JDK default");
        assertNotNull(client.connectTimeout().orElse(null),
            "the connect timeout stays wired alongside it");
    }

    @Test
    void withoutConfiguredSecurityTheJdkDefaultStands() {
        HttpClient client = PeerConnector.newClient(Duration.ofSeconds(1), null);

        // A default context exists (the JDK's), it just is not the configured one.
        assertNotNull(client.sslContext());
    }
}
