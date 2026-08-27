// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package org.dsi.dpn.common.service.secret;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.ReloadableX509KeyManager;
import org.dsi.dpn.common.utils.ReloadableX509TrustManager;
import org.dsi.dpn.common.exception.FederatorSslException;

/**
 * Central switch for sourcing the federator's mTLS material from Vault instead of files.
 * <p>
 *     When {@code vault.tls.enabled=true} (in the common configuration), both the producer
 *     (gRPC server) and consumer (gRPC client) — as well as the IDP HTTP client — build their
 *     key/trust material in memory from the certificate material the certificate manager
 *     publishes to Vault, so the shared Azure SMB file share is no longer required.
 * </p>
 * <p>
 *     {@link #keyManagers()}, {@link #trustManagers()} and {@link #sslContext()} all return
 *     wrappers around one process-wide {@link ReloadableX509KeyManager}/
 *     {@link ReloadableX509TrustManager} pair, refreshed from Vault on a schedule (see
 *     {@link #VAULT_TLS_RELOAD_INTERVAL_SECONDS}) — ported from dpn-federator's
 *     {@code GRPCServer} certificate-hot-reload mechanism, generalised so every caller benefits
 *     without needing its own reload logic. A caller that builds its {@code SSLContext}/
 *     {@code HttpClient}/{@code RestTemplate}/{@code WebClient} once and holds onto it (as
 *     {@code OcspVerificationServiceImpl}, {@code JwtDecoderConfig}, the gateway's
 *     {@code backendRestTemplate} bean, and {@code RestClient.buildWebClient()} all do) still
 *     presents a freshly-rotated Vault certificate on every new TLS handshake after that,
 *     because JSSE consults the key/trust manager at handshake time, not at
 *     {@code SSLContext}-build time — swapping the reloadable managers' delegate is enough.
 *     Already-established connections keep whatever they negotiated; only new ones observe an
 *     update. Callers that instead read {@link #keyManagers()}/{@link #trustManagers()} fresh
 *     per call ({@code ManagementNodeDataHandler}, {@code IdpTokenServicePrivateJwtImpl} via
 *     their {@code Supplier<HttpClient>}) were already dynamic before this and remain so.
 * </p>
 */
public final class VaultTlsSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultTlsSupport.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Common-config property key that resolves to the common configuration file path. */
    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";

    public static final String VAULT_TLS_ENABLED = "vault.tls.enabled";
    public static final String VAULT_TLS_SECRET_BASE_PATH = "vault.tls.secret-base-path";
    public static final String VAULT_TLS_KEYSTORE_ALIAS = "vault.tls.keystore-alias";
    public static final String DEFAULT_SECRET_BASE_PATH = "node-net/client";
    public static final String DEFAULT_KEYSTORE_ALIAS = "federator";

    /**
     * How often, in seconds, to reload the key/trust material from Vault. {@code 0} (or negative)
     * disables the scheduled reload — matches dpn-federator's
     * {@code server.certReloadIntervalSeconds} semantics, generalised to a shared property name
     * since this reload serves every Vault-TLS consumer in the process, not just one server.
     */
    public static final String VAULT_TLS_RELOAD_INTERVAL_SECONDS = "vault.tls.reload.interval.seconds";
    private static final String DEFAULT_RELOAD_INTERVAL_SECONDS = "3600";
    private static final String RELOADER_THREAD_NAME = "vault-tls-cert-reloader";

    private static final Object INIT_LOCK = new Object();
    private static volatile ReloadableX509KeyManager reloadableKeyManager;
    private static volatile ReloadableX509TrustManager reloadableTrustManager;

    private VaultTlsSupport() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /** @return true when the federator should build its mTLS material from Vault. */
    public static boolean isVaultTlsEnabled() {
        return Boolean.parseBoolean(commonConfig().getProperty(VAULT_TLS_ENABLED, "true"));
    }

    /**
     * KeyManagers for the identity certificate. The returned array always wraps the same shared,
     * periodically-reloaded {@link ReloadableX509KeyManager} — see the class Javadoc.
     */
    public static KeyManager[] keyManagers() {
        ensureReloadableManagersInitialised();
        return new KeyManager[] {reloadableKeyManager};
    }

    /**
     * TrustManagers for the CA chain. The returned array always wraps the same shared,
     * periodically-reloaded {@link ReloadableX509TrustManager} — see the class Javadoc.
     */
    public static TrustManager[] trustManagers() {
        ensureReloadableManagersInitialised();
        return new TrustManager[] {reloadableTrustManager};
    }

    /**
     * An mTLS {@link SSLContext} whose key/trust material is read from Vault and kept fresh by the
     * shared reload schedule — see the class Javadoc for why building this once and holding onto it
     * is safe.
     */
    public static SSLContext sslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers(), trustManagers(), null);
            return ctx;
        } catch (FederatorSslException e) {
            throw e;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to build SSLContext from Vault material", e);
        }
    }

    private static void ensureReloadableManagersInitialised() {
        if (reloadableKeyManager != null) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (reloadableKeyManager != null) {
                return;
            }
            reloadableKeyManager = new ReloadableX509KeyManager(freshKeyManager());
            reloadableTrustManager = new ReloadableX509TrustManager(freshTrustManager());
            scheduleReload();
        }
    }

    private static void scheduleReload() {
        long intervalSeconds = parseIntervalSeconds();
        if (intervalSeconds <= 0) {
            LOGGER.info("Vault TLS certificate hot-reload is disabled ({}={})",
                    VAULT_TLS_RELOAD_INTERVAL_SECONDS, intervalSeconds);
            return;
        }
        ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, RELOADER_THREAD_NAME);
            t.setDaemon(true);
            return t;
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory);
        scheduler.scheduleAtFixedRate(
                VaultTlsSupport::reloadSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("Scheduled Vault TLS certificate hot-reload every {} seconds", intervalSeconds);
    }

    /** Re-reads the identity cert and CA chain from Vault and swaps them into the shared managers. */
    private static void reloadSafely() {
        try {
            reloadableKeyManager.setDelegate(freshKeyManager());
            reloadableTrustManager.setDelegate(freshTrustManager());
            LOGGER.info("Reloaded Vault TLS certificate material");
        } catch (Exception e) {
            // Never let a failed reload kill the scheduled task or the currently-loaded material.
            LOGGER.warn("Vault TLS certificate reload failed — keeping previously loaded material: {}",
                    e.getMessage());
        }
    }

    private static long parseIntervalSeconds() {
        try {
            return Long.parseLong(
                    commonConfig().getProperty(VAULT_TLS_RELOAD_INTERVAL_SECONDS, DEFAULT_RELOAD_INTERVAL_SECONDS)
                            .trim());
        } catch (NumberFormatException e) {
            return Long.parseLong(DEFAULT_RELOAD_INTERVAL_SECONDS);
        }
    }

    private static X509KeyManager freshKeyManager() {
        Properties common = commonConfig();
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        String basePath = common.getProperty(VAULT_TLS_SECRET_BASE_PATH, DEFAULT_SECRET_BASE_PATH);
        String alias = common.getProperty(VAULT_TLS_KEYSTORE_ALIAS, DEFAULT_KEYSTORE_ALIAS);
        KeyManager[] raw = VaultKeystoreProvider.keyManagers(provider, basePath, alias, ephemeralPassword());
        return firstX509KeyManager(raw);
    }

    private static X509TrustManager freshTrustManager() {
        Properties common = commonConfig();
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        String basePath = common.getProperty(VAULT_TLS_SECRET_BASE_PATH, DEFAULT_SECRET_BASE_PATH);
        TrustManager[] raw = VaultKeystoreProvider.trustManagers(provider, basePath, ephemeralPassword());
        return firstX509TrustManager(raw);
    }

    private static X509KeyManager firstX509KeyManager(KeyManager[] managers) {
        for (KeyManager m : managers) {
            if (m instanceof X509KeyManager x509) {
                return x509;
            }
        }
        throw new FederatorSslException("No X509KeyManager found in Vault-sourced KeyManagerFactory output");
    }

    private static X509TrustManager firstX509TrustManager(TrustManager[] managers) {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new FederatorSslException("No X509TrustManager found in Vault-sourced TrustManagerFactory output");
    }

    private static Properties commonConfig() {
        try {
            return PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
        } catch (Exception e) {
            LOGGER.warn("Could not load common configuration for Vault TLS: {}", e.getMessage());
            return new Properties();
        }
    }

    private static char[] ephemeralPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
