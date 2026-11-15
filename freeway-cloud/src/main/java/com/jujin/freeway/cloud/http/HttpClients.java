package com.jujin.freeway.cloud.http;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/**
 * The one place an outbound {@link HttpClient} starts: connect timeout and,
 * when configured, the transport's TLS context.
 *
 * <p>Cloud paths open clients — RPC ({@code RpcHttpClientDefault}), Fleet
 * writes (in ext) and the mesh dialer ({@code PeerConnector}) — and each had
 * its own builder with the same two
 * rules. They differ in what they add afterwards; the base is shared.</p>
 */
public final class HttpClients {

    private HttpClients() {}

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
