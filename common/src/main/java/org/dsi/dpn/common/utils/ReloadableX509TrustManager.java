// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
// Ported from dpn-federator's common.utils.ReloadableX509TrustManager (same class, same behaviour).
package org.dsi.dpn.common.utils;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * An {@link X509ExtendedTrustManager} that forwards every call to a swappable underlying trust
 * manager.
 * <p>
 * The delegate can be replaced atomically at runtime via {@link #setDelegate(X509TrustManager)} —
 * for example after the Vault-sourced CA chain has rotated to add or remove a trust anchor. JSSE
 * consults the trust manager during each handshake, so swapping the delegate causes all subsequent
 * mTLS handshakes to be validated against the freshly loaded trust anchors, with no rebuild of
 * whatever holds a reference to this instance and therefore zero downtime. Already-established
 * connections are unaffected.
 * <p>
 * The reference is {@code volatile} so a swap performed on the reload thread is immediately visible
 * to handshake threads without further synchronisation.
 */
public final class ReloadableX509TrustManager extends X509ExtendedTrustManager {

    private volatile X509TrustManager delegate;

    /**
     * Creates a reloadable trust manager wrapping the supplied delegate.
     *
     * @param delegate the trust manager to forward to initially; must not be {@code null}
     */
    public ReloadableX509TrustManager(X509TrustManager delegate) {
        this.delegate = requireNonNull(delegate);
    }

    /**
     * Atomically replaces the underlying trust manager. Subsequent handshakes use the new delegate.
     *
     * @param newDelegate the replacement trust manager; must not be {@code null}
     */
    public void setDelegate(X509TrustManager newDelegate) {
        this.delegate = requireNonNull(newDelegate);
    }

    private static X509TrustManager requireNonNull(X509TrustManager trustManager) {
        if (trustManager == null) {
            throw new IllegalArgumentException("Delegate X509TrustManager must not be null");
        }
        return trustManager;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkClientTrusted(chain, authType, socket);
        } else {
            current.checkClientTrusted(chain, authType);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkServerTrusted(chain, authType, socket);
        } else {
            current.checkServerTrusted(chain, authType);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkClientTrusted(chain, authType, engine);
        } else {
            current.checkClientTrusted(chain, authType);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkServerTrusted(chain, authType, engine);
        } else {
            current.checkServerTrusted(chain, authType);
        }
    }
}
