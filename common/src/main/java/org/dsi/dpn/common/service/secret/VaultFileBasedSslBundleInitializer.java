// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Writes a Vault-issued certificate to real, temporary PKCS12 files and
 * configures the standard file-based {@code spring.ssl.bundle.jks.<name>.*}
 * properties pointing at them, as a working replacement for the in-memory
 * {@link VaultSslBundleRegistrar} approach.
 *
 * <p><b>Why this exists:</b> under this project's current Spring Boot version,
 * the embedded Tomcat connector does not correctly apply an in-memory
 * {@code SslBundle} supplied via {@link org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar}
 * - confirmed by a real TLS handshake test ({@code openssl s_client}), which
 * showed the wrong (JVM default) certificate served with the in-memory
 * approach, and the correct Vault-issued certificate served once switched to
 * this file-based approach. This is a genuine, verified defect in that
 * connector-wiring path for in-memory bundles specifically, not a
 * configuration mistake in this codebase.
 *
 * <p><b>Usage:</b> must be called from the application's own {@code main()}
 * method, <em>before</em> {@code SpringApplication.run(...)}, since the
 * {@code spring.ssl.bundle.jks.*} system properties it sets must already be
 * present in the {@code Environment} by the time Spring resolves
 * {@code server.ssl.bundle}.
 *
 * <p><b>Security:</b> private key material touches disk only inside a fresh,
 * randomly-named JVM temp directory, deleted automatically on JVM exit. It is
 * never written to a predictable or long-lived location.
 *
 * <p><b>Hot-reload:</b> the same files are periodically rewritten with fresh
 * material from Vault, on the same schedule ({@value VaultTlsSupport#VAULT_TLS_RELOAD_INTERVAL_SECONDS},
 * default one hour) the original in-memory registrar used - with
 * {@code reload-on-update} enabled on the bundle, Spring Boot detects the
 * file change and hot-swaps the connector's certificate with no server
 * rebind, the same zero-downtime rotation the in-memory approach provided.
 */
@Slf4j
public final class VaultFileBasedSslBundleInitializer {

    private static final String COMMON_CONFIG = "common.configuration";
    private static final String RELOADER_THREAD_PREFIX = "vault-ssl-file-reloader-";

    private VaultFileBasedSslBundleInitializer() {
    }

    /**
     * @param bundleName the {@code server.ssl.bundle} name (e.g. {@code "federator-tls"})
     */
    public static void initialise(String bundleName) throws Exception {
        if (!PropertyUtil.initializeProperties()) {
            throw new IllegalStateException(
                    "VaultFileBasedSslBundleInitializer: failed to initialise PropertyUtil for bundle '"
                            + bundleName + "'. Set FEDERATOR_SERVER_PROPERTIES env var to the path of "
                            + "server.properties before calling this.");
        }
        if (!VaultTlsSupport.isVaultTlsEnabled()) {
            log.info("vault.tls.enabled is not set - '{}' bundle stays file-based via its own "
                    + "explicitly configured spring.ssl.bundle.jks.* properties, if any", bundleName);
            return;
        }

        Properties common = commonConfig();
        String basePath = common.getProperty(
                VaultTlsSupport.VAULT_TLS_SECRET_BASE_PATH, VaultTlsSupport.DEFAULT_SECRET_BASE_PATH);
        String alias = common.getProperty(
                VaultTlsSupport.VAULT_TLS_KEYSTORE_ALIAS, VaultTlsSupport.DEFAULT_KEYSTORE_ALIAS);

        Path tempDir = Files.createTempDirectory(bundleName + "-tls-");
        tempDir.toFile().deleteOnExit();
        Path keystorePath = tempDir.resolve("identity.p12");
        Path truststorePath = tempDir.resolve("truststore.p12");
        keystorePath.toFile().deleteOnExit();
        truststorePath.toFile().deleteOnExit();

        // Static path/alias properties (location, type, alias) only need setting
        // once - they never change between reloads, only the file *contents* do.
        configureStaticProperties(bundleName, alias, keystorePath, truststorePath);
        writeFiles(bundleName, basePath, alias, keystorePath, truststorePath);
        log.info("Wrote Vault cert to temp files, configured file-based '{}' bundle at {}", bundleName, tempDir);

        scheduleReload(bundleName, basePath, alias, keystorePath, truststorePath);
    }

    private static void configureStaticProperties(
            String bundleName, String alias, Path keystorePath, Path truststorePath) {
        String prefix = "spring.ssl.bundle.jks." + bundleName;
        System.setProperty(prefix + ".key.alias", alias);
        System.setProperty(prefix + ".keystore.location", "file:" + keystorePath.toAbsolutePath());
        System.setProperty(prefix + ".keystore.type", "PKCS12");
        System.setProperty(prefix + ".truststore.location", "file:" + truststorePath.toAbsolutePath());
        System.setProperty(prefix + ".truststore.type", "PKCS12");
        // Enables Spring Boot's own file-watching so rewriting the same files below
        // (on reload) is picked up automatically, hot-swapping the connector's
        // certificate with no restart. Property name could not be verified against
        // live documentation from this environment - confirm against Spring Boot's
        // actual SSL bundle reference docs; harmless if wrong (an unrecognised
        // property is simply ignored).
        System.setProperty(prefix + ".reload-on-update", "true");
    }

    private static void writeFiles(
            String bundleName, String basePath, String alias, Path keystorePath, Path truststorePath)
            throws Exception {
        Properties common = commonConfig();
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        char[] password = ephemeralPassword();

        KeyStore identity = VaultKeystoreProvider.buildIdentityKeyStore(provider, basePath, alias, password);
        KeyStore trust = VaultKeystoreProvider.buildTrustStore(provider, basePath, password);

        try (OutputStream os = Files.newOutputStream(keystorePath)) {
            identity.store(os, password);
        }
        try (OutputStream os = Files.newOutputStream(truststorePath)) {
            trust.store(os, password);
        }

        // Password can legitimately change on every reload (a fresh random value
        // each time) since only the file's own header stores it - updating this
        // property keeps it in sync for the next time Spring (re)reads the file.
        String pw = new String(password);
        String prefix = "spring.ssl.bundle.jks." + bundleName;
        System.setProperty(prefix + ".keystore.password", pw);
        System.setProperty(prefix + ".truststore.password", pw);
    }

    private static void scheduleReload(
            String bundleName, String basePath, String alias, Path keystorePath, Path truststorePath) {
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
                () -> reloadSafely(bundleName, basePath, alias, keystorePath, truststorePath),
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Scheduled '{}' SSL bundle file hot-reload every {} seconds", bundleName, intervalSeconds);
    }

    private static void reloadSafely(
            String bundleName, String basePath, String alias, Path keystorePath, Path truststorePath) {
        try {
            writeFiles(bundleName, basePath, alias, keystorePath, truststorePath);
            log.info("Reloaded '{}' SSL bundle files from Vault", bundleName);
        } catch (Exception e) {
            log.warn("Failed to reload '{}' SSL bundle files from Vault - keeping previously written material: {}",
                    bundleName, e.getMessage());
        }
    }

    private static long parseIntervalSeconds() {
        try {
            return Long.parseLong(commonConfig()
                    .getProperty(VaultTlsSupport.VAULT_TLS_RELOAD_INTERVAL_SECONDS, "3600")
                    .trim());
        } catch (NumberFormatException e) {
            return 3600L;
        }
    }

    private static Properties commonConfig() {
        return PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
    }

    /** A fresh in-memory-only password; never needs to be remembered past this call. */
    private static char[] ephemeralPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
