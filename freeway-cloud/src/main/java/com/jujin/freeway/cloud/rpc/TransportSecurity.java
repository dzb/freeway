package com.jujin.freeway.cloud.rpc;

import javax.net.ssl.SSLContext;

/**
 * Outbound transport security. The default is {@link #NONE} (plaintext
 * development); file-backed mTLS arrives via {@link TransportSecurityDefault}
 * (the framework default, activated by the keystore configuration keys
 * {@code freeway.cloud.rpc.tls.*}); dynamic certificate sources (Vault) are an
 * ext concern — bind an alternative implementation with {@code .primary()}.
 */
public interface TransportSecurity {

    /** SSL context for outbound calls, or {@code null} for the JDK default (no client auth). */
    SSLContext sslContext();

    TransportSecurity NONE = () -> null;
}
