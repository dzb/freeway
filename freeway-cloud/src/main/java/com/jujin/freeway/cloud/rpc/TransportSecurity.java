package com.jujin.freeway.cloud.rpc;

import javax.net.ssl.SSLContext;

/**
 * Outbound transport security, honored by <b>every</b> outbound path: the RPC
 * client ({@link CloudHttpClientDefault}) and the event mesh's dialer
 * ({@code PeerConnector}) both take their SSL context from here, so a
 * {@code wss://} peer and an {@code https://} service see the same client
 * identity and the same trust anchors.
 *
 * <p>The default is {@link #NONE} (plaintext development); file-backed mTLS
 * arrives via {@link TransportSecurityDefault} (the framework default,
 * activated by the keystore configuration keys {@code freeway.cloud.rpc.tls.*});
 * dynamic certificate sources (Vault) are an ext concern — bind an alternative
 * implementation with {@code .primary()}. The context is resolved once when the
 * component that uses it is built, so a rotated keystore takes effect on
 * restart (the HTTP server has its own {@code freeway.http.ssl.reload-interval}
 * for the inbound side).</p>
 */
public interface TransportSecurity {

    /** SSL context for outbound calls, or {@code null} for the JDK default (no client auth). */
    SSLContext sslContext();

    TransportSecurity NONE = () -> null;
}
