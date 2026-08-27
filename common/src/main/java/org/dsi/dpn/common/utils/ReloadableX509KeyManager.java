// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
// Ported from dpn-federator's common.utils.ReloadableX509KeyManager (same class, same behaviour).
package org.dsi.dpn.common.utils;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

/**
 * An {@link X509ExtendedKeyManager} that forwards every call to a swappable underlying key manager.
 * <p>
 * The delegate can be replaced atomically at runtime via {@link #setDelegate(X509KeyManager)} — for
 * example after the Vault-sourced identity certificate has rotated. Because JSSE consults the key
 * manager on <em>every new TLS handshake</em>, swapping the delegate causes all subsequent handshakes
 * to present the freshly loaded certificate and private key, without rebuilding or rebinding whatever
 * {@link javax.net.ssl.SSLContext}/{@code HttpClient}/{@code RestTemplate}/{@code SslBundle} already
 * holds a reference to this instance — certificate rotation incurs zero downtime. Connections that are
 * already established keep the material they negotiated; only new connections observe the update.
 * <p>
 * The reference is {@code volatile} so a swap performed on the reload thread is immediately visible
 * to handshake threads without further synchronisation.
 */
public final class ReloadableX509KeyManager extends X509ExtendedKeyManager {

    private volatile X509KeyManager delegate;

    /**
     * Creates a reloadable key manager wrapping the supplied delegate.
     *
     * @param delegate the key manager to forward to initially; must not be {@code null}
     */
    public ReloadableX509KeyManager(X509KeyManager delegate) {
        this.delegate = requireNonNull(delegate);
    }

    /**
     * Atomically replaces the underlying key manager. Subsequent handshakes use the new delegate.
     *
     * @param newDelegate the replacement key manager; must not be {@code null}
     */
    public void setDelegate(X509KeyManager newDelegate) {
        this.delegate = requireNonNull(newDelegate);
    }

    private static X509KeyManager requireNonNull(X509KeyManager keyManager) {
        if (keyManager == null) {
            throw new IllegalArgumentException("Delegate X509KeyManager must not be null");
        }
        return keyManager;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return delegate.getClientAliases(keyType, issuers);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return delegate.getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return delegate.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return delegate.getPrivateKey(alias);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        X509KeyManager current = delegate;
        if (current instanceof X509ExtendedKeyManager extended) {
            return extended.chooseEngineClientAlias(keyType, issuers, engine);
        }
        return current.chooseClientAlias(keyType, issuers, null);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        X509KeyManager current = delegate;
        if (current instanceof X509ExtendedKeyManager extended) {
            return extended.chooseEngineServerAlias(keyType, issuers, engine);
        }
        return current.chooseServerAlias(keyType, issuers, null);
    }
}
