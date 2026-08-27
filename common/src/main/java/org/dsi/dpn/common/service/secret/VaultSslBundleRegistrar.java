// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
 * <p><b>Reload:</b> once registered, a background task rebuilds the bundle from Vault and calls
 * {@link SslBundleRegistry#updateBundle} on the same schedule as {@link VaultTlsSupport}'s shared
 * key/trust manager reload ({@value VaultTlsSupport#VAULT_TLS_RELOAD_INTERVAL_SECONDS}, default one
 * hour). Spring Boot's embedded connectors register a listener on the {@code SslBundle} they were
 * given ({@code SslBundle.registerBundleUpdateHandler}), so {@code updateBundle} hot-swaps the
 * inbound listener's certificate for new handshakes with no server rebind — the same zero-downtime
 * rotation dpn-federator's gRPC server gets from its own {@code ReloadableX509KeyManager}/
 * {@code ReloadableX509TrustManager}, ported for the other Vault-TLS consumers in
 * {@link VaultTlsSupport}. Already-established connections are unaffected.
 */
@Slf4j
public class VaultSslBundleRegistrar implements SslBundleRegistrar {

    private static final String COMMON_CONFIG = "common.configuration";
    private static final String RELOADER_THREAD_PREFIX = "vault-ssl-bundle-reloader-";

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

        String basePath = commonConfig().getProperty(
                VaultTlsSupport.VAULT_TLS_SECRET_BASE_PATH, VaultTlsSupport.DEFAULT_SECRET_BASE_PATH);
        String alias = commonConfig().getProperty(
                VaultTlsSupport.VAULT_TLS_KEYSTORE_ALIAS, VaultTlsSupport.DEFAULT_KEYSTORE_ALIAS);

        try {
            registry.registerBundle(bundleName, buildBundle(basePath, alias));
            log.info("Registered '{}' SSL bundle from Vault (base '{}', alias '{}')",
                    bundleName, basePath, alias);
        } catch (Exception e) {
            // Thrown from an autoconfiguration-time callback: surfacing a clear cause here
            // is the only chance to avoid a confusing downstream NPE/ClassCastException.
            throw new IllegalStateException(
                    "Failed to build the '" + bundleName + "' SSL bundle from Vault", e);
        }

        scheduleReload(registry, basePath, alias);
    }

    private void scheduleReload(SslBundleRegistry registry, String basePath, String alias) {
        long intervalSeconds = parseIntervalSeconds();
        if (intervalSeconds <= 0) {
            log.info("'{}' SSL bundle hot-reload is disabled ({}={})",
                    bundleName, VaultTlsSupport.VAULT_TLS_RELOAD_INTERVAL_SECONDS, intervalSeconds);
            return;
        }
        ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, RELOADER_THREAD_PREFIX + bundleName);
            t.setDaemon(true);
            return t;
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory);
        scheduler.scheduleAtFixedRate(
                () -> reloadSafely(registry, basePath, alias), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Scheduled '{}' SSL bundle hot-reload every {} seconds", bundleName, intervalSeconds);
    }

    private void reloadSafely(SslBundleRegistry registry, String basePath, String alias) {
        try {
            registry.updateBundle(bundleName, buildBundle(basePath, alias));
            log.info("Reloaded '{}' SSL bundle from Vault", bundleName);
        } catch (Exception e) {
            // Never let a failed reload kill the scheduled task or the currently-loaded bundle.
            log.warn("Failed to reload '{}' SSL bundle from Vault — keeping previously loaded material: {}",
                    bundleName, e.getMessage());
        }
    }

    private SslBundle buildBundle(String basePath, String alias) throws Exception {
        Properties common = commonConfig();
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        char[] password = ephemeralPassword();
        KeyStore identity = VaultKeystoreProvider.buildIdentityKeyStore(provider, basePath, alias, password);
        KeyStore trust = VaultKeystoreProvider.buildTrustStore(provider, basePath, password);
        SslStoreBundle stores = SslStoreBundle.of(identity, new String(password), trust);
        return SslBundle.of(stores);
    }

    private long parseIntervalSeconds() {
        try {
            return Long.parseLong(commonConfig()
                    .getProperty(VaultTlsSupport.VAULT_TLS_RELOAD_INTERVAL_SECONDS, "3600")
                    .trim());
        } catch (NumberFormatException e) {
            return 3600L;
        }
    }

    private Properties commonConfig() {
        return PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
    }

    /** A fresh in-memory-only password; never needs to be remembered past this call. */
    private char[] ephemeralPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
