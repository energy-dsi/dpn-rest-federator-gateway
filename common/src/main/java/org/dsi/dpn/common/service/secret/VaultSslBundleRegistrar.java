// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.boot.ssl.SslStoreBundle;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Sources a Spring Boot app's own inbound HTTPS/mTLS listener from Vault, when enabled —
 * the counterpart, for the server side, of what {@code HttpClientFactoryUtils.createHttpClientWithMtls}
 * already does for outbound calls.
 *
 * <p>Shared by any app whose {@code server.ssl.bundle} needs a Vault-sourced identity —
 * currently rest-federator-server and demo-runner-server both wire this in as a
 * {@code @Bean} (not {@code @Component}: this class lives in {@code common}, outside
 * either app's own package tree, so Spring's default component scan wouldn't find it
 * here — each app declares the bean itself in its own config class instead).
 *
 * <p><b>Why this exists:</b> {@code server.ssl.bundle=&lt;name&gt;} plus the
 * {@code spring.ssl.bundle.jks.&lt;name&gt;.*} properties is Spring Boot's own declarative
 * SSL bundle mechanism for the embedded Tomcat connector — a completely separate code
 * path from {@link VaultTlsSupport} that always expects a real keystore <em>file</em> at
 * the configured location, with no awareness of Vault. With no file ever written to
 * disk, the app fails to start with {@code FileNotFoundException} the moment
 * {@code vault.tls.enabled=true} unless something else registers that bundle.
 *
 * <p>{@link SslBundleRegistrar} is the extension point Spring Boot 3.1+ provides for
 * exactly this: supplying an {@link SslBundle} from any source, not just files. When
 * {@code vault.tls.enabled=true}, this registrar <b>replaces</b> the named bundle with
 * one built directly from the same in-memory {@link KeyStore} material
 * {@code VaultTlsSupport} reads for outbound calls — the identity certificate and key
 * are never written to disk for the inbound listener either.
 *
 * <p>When {@code vault.tls.enabled} is not set, this registrar does nothing and Spring's
 * own file-based bundle registration (if configured) is unaffected.
 *
 * <p><b>Known limitation:</b> the bundle is built once, at startup. Unlike a file-based
 * bundle with {@code reload-on-update=true}, a certificate rotated in Vault after startup
 * is not picked up automatically — the pod must restart, or a future enhancement could
 * call {@link SslBundleRegistry#updateBundle} on a schedule.
 */
@Slf4j
public class VaultSslBundleRegistrar implements SslBundleRegistrar {

    private static final String COMMON_CONFIG = "common.configuration";

    private final String bundleName;

    /** @param bundleName the {@code server.ssl.bundle} name this registrar supplies */
    public VaultSslBundleRegistrar(String bundleName) {
        this.bundleName = bundleName;
    }

    @Override
    public void registerBundles(SslBundleRegistry registry) {
        if (!VaultTlsSupport.isVaultTlsEnabled()) {
            log.info("vault.tls.enabled is not set — '{}' bundle stays file-based", bundleName);
            return;
        }

        try {
            Properties common = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
            SecretProvider provider = PropertyUtil.createSecretProvider(common);
            String basePath = common.getProperty(
                    VaultTlsSupport.VAULT_TLS_SECRET_BASE_PATH, VaultTlsSupport.DEFAULT_SECRET_BASE_PATH);
            String alias = common.getProperty(
                    VaultTlsSupport.VAULT_TLS_KEYSTORE_ALIAS, VaultTlsSupport.DEFAULT_KEYSTORE_ALIAS);

            char[] password = ephemeralPassword();
            KeyStore identity = VaultKeystoreProvider.buildIdentityKeyStore(provider, basePath, alias, password);
            KeyStore trust = VaultKeystoreProvider.buildTrustStore(provider, basePath, password);

            SslStoreBundle stores = SslStoreBundle.of(identity, new String(password), trust);
            registry.registerBundle(bundleName, SslBundle.of(stores));

            log.info("Registered '{}' SSL bundle from Vault (base '{}', alias '{}')",
                    bundleName, basePath, alias);
        } catch (Exception e) {
            // Thrown from an autoconfiguration-time callback: surfacing a clear cause here
            // is the only chance to avoid a confusing downstream NPE/ClassCastException.
            throw new IllegalStateException(
                    "Failed to build the '" + bundleName + "' SSL bundle from Vault", e);
        }
    }

    /** A fresh in-memory-only password; never needs to be remembered past this call. */
    private char[] ephemeralPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
