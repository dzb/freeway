package com.jujin.freeway.cloud.rpc;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * The client identity as a delegate: every alias choice reads the material the
 * connection currently has, so a rotated client certificate is presented on the
 * next handshake without touching the {@code SSLContext} or the clients built
 * from it.
 *
 * <p>Only the client side is answered — this is outbound material, and a
 * server alias from an outbound context has no meaning. Returning {@code null}
 * when no key manager is available is what "no client identity configured"
 * already looked like.</p>
 */
final class ReloadingKeyManager extends X509ExtendedKeyManager {

    private final RotatingTlsMaterial material;

    ReloadingKeyManager(RotatingTlsMaterial material) {
        this.material = material;
    }

    private X509ExtendedKeyManager delegate() {
        return RotatingTlsMaterial.firstOf(
            material.current().keyManagers(), X509ExtendedKeyManager.class);
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        X509ExtendedKeyManager delegate = delegate();
        return delegate == null ? null : delegate.getClientAliases(keyType, issuers);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        X509ExtendedKeyManager delegate = delegate();
        return delegate == null ? null : delegate.chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return null; // outbound material cannot serve a TLS server role
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        X509ExtendedKeyManager delegate = delegate();
        return delegate == null
            ? null
            : delegate.chooseEngineClientAlias(keyType, issuers, engine);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        return null;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        X509ExtendedKeyManager delegate = delegate();
        return delegate == null ? null : delegate.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        X509ExtendedKeyManager delegate = delegate();
        return delegate == null ? null : delegate.getPrivateKey(alias);
    }
}
