package com.jujin.freeway.cloud.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/**
 * The one place an outbound {@link HttpClient} starts: connect timeout and,
 * when configured, the transport's TLS context.
 *
 * <p>Three cloud paths open clients — RPC ({@code CloudHttpClientDefault}),
 * registry writes ({@code RegistryNodeClient}) and the mesh dialer
 * ({@code PeerConnector}) — and each had its own builder with the same two
 * rules. They differ in what they add afterwards; the base is shared.</p>
 */
public final class CloudHttpClients {

    private CloudHttpClients() {}

    public static HttpClient.Builder builder(Duration connectTimeout, SSLContext sslContext) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder;
    }
}
