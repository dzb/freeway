package com.jujin.freeway.cloud.rpc;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The trust anchors as a delegate: every peer check reads the material the
 * connection currently has, so a rotated truststore takes effect on the next
 * handshake — the case a CA rotation produces, and the one a restart-only policy
 * answers with a rolling restart of every process.
 *
 * <p>A delegate that is not engine-aware (an older provider) still works: the
 * {@code SSLEngine} overloads fall back to the socket-shaped check, which is
 * what the JDK default managers implement anyway.</p>
 */
final class ReloadingTrustManager extends X509ExtendedTrustManager {

    private final RotatingTlsMaterial material;

    ReloadingTrustManager(RotatingTlsMaterial material) {
        this.material = material;
    }

    private X509TrustManager delegate() {
        return RotatingTlsMaterial.firstOf(
            material.current().trustManagers(), X509TrustManager.class);
    }

    private X509ExtendedTrustManager extended() {
        return RotatingTlsMaterial.firstOf(
            material.current().trustManagers(), X509ExtendedTrustManager.class);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        X509ExtendedTrustManager delegate = extended();
        if (delegate != null) {
            delegate.checkClientTrusted(chain, authType, socket);
            return;
        }
        requireDelegate().checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        X509ExtendedTrustManager delegate = extended();
        if (delegate != null) {
            delegate.checkServerTrusted(chain, authType, socket);
            return;
        }
        requireDelegate().checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        X509ExtendedTrustManager delegate = extended();
        if (delegate != null) {
            delegate.checkClientTrusted(chain, authType, engine);
            return;
        }
        requireDelegate().checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        X509ExtendedTrustManager delegate = extended();
        if (delegate != null) {
            delegate.checkServerTrusted(chain, authType, engine);
            return;
        }
        requireDelegate().checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        requireDelegate().checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        requireDelegate().checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        X509TrustManager delegate = delegate();
        return delegate == null ? new X509Certificate[0] : delegate.getAcceptedIssuers();
    }

    private X509TrustManager requireDelegate() throws CertificateException {
        X509TrustManager delegate = delegate();
        if (delegate == null) {
            throw new CertificateException("no trust manager configured");
        }
        return delegate;
    }
}
