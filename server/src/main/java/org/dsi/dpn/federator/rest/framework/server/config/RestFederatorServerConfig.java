// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Properties;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.dsi.dpn.federator.rest.framework.server.proxy.BackendProperties;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.management.ManagementNodeDataHandler;
import org.dsi.dpn.common.service.config.ProducerConfigService;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.storage.InMemoryConfigurationStore;
import org.dsi.dpn.common.utils.IdpTokenServiceFactory;
import org.dsi.dpn.common.utils.HttpClientFactoryUtils;
import org.dsi.dpn.common.utils.ObjectMapperUtil;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationService;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationServiceImpl;

/**
 * FRAMEWORK - do not modify.
 *
 * Wires existing gRPC Federator common components as Spring beans.
 * Also wires OcspVerificationService used by DsiProductAuthorizationFilter
 * for Stage 2 certificate revocation check.
 */
@Configuration
@EnableConfigurationProperties(BackendProperties.class)
@Slf4j
public class RestFederatorServerConfig {

    private static final String COMMON_CONFIG = "common.configuration";
    private static final String VAULT_TRUSTSTORE_PATH = "vault.truststore.path";
    private static final String VAULT_TRUSTSTORE_PASSWORD = "vault.truststore.password";

    static {
        if (!PropertyUtil.initializeProperties()) {
            throw new IllegalStateException(
                "REST Federator Server: failed to initialise PropertyUtil. "
                + "Set FEDERATOR_SERVER_PROPERTIES env var to path of server.properties.");
        }
        log.info("PropertyUtil initialised for REST Federator Server");
    }

    // -- DISABLED - replaced by the file-based approach ----------------------
    // VaultFileBasedSslBundleInitializer.initialise("federator-tls") in
    // RestFederatorServerApplication.main() now handles this. The in-memory
    // SslBundleRegistrar approach below is confirmed not to work correctly
    // under this project's current Spring Boot version - verified via a real
    // TLS handshake test (openssl s_client) showing the wrong certificate
    // served. Kept here, commented, in case that connector-wiring defect is
    // fixed in a future Spring Boot version and this simpler approach can be
    // restored.
    //
    // @Bean
    // public static SslBundleRegistrar vaultSslBundleRegistrar() {
    //     return new VaultSslBundleRegistrar("federator-tls");
    // }

    // -- ALSO DISABLED - disproven theory, kept for reference ----------------
    // This forced dependsOn ordering; we later proved (via openssl s_client)
    // that ordering was never the actual problem - registration already ran
    // before Tomcat initialised, and the wrong certificate was still served.
    // Left commented rather than deleted, as a record of what was ruled out.
    //
    // @Bean
    // public static org.springframework.beans.factory.config.BeanFactoryPostProcessor tomcatSslOrderingFix() {
    //     return beanFactory -> {
    //         String[] factoryBeanNames = beanFactory.getBeanNamesForType(
    //                 org.springframework.boot.web.server.servlet.ServletWebServerFactory.class, false, false);
    //         for (String beanName : factoryBeanNames) {
    //             org.springframework.beans.factory.config.BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
    //             String[] existing = bd.getDependsOn();
    //             String[] updated;
    //             if (existing == null || existing.length == 0) {
    //                 updated = new String[] {"sslBundleRegistry"};
    //             } else {
    //                 updated = java.util.Arrays.copyOf(existing, existing.length + 1);
    //                 updated[existing.length] = "sslBundleRegistry";
    //             }
    //             bd.setDependsOn(updated);
    //         }
    //     };
    // }

    @Bean
    public IdpTokenService idpTokenService() {
        log.info("Creating IdpTokenService via IdpTokenServiceFactory");
        return IdpTokenServiceFactory.createIdpTokenService();
    }

    @Bean
    public ManagementNodeDataHandler managementNodeDataHandler(IdpTokenService idpTokenService) {
        Properties props = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
        return new ManagementNodeDataHandler(
                () -> HttpClientFactoryUtils.createHttpClientWithMtls(props),
                ObjectMapperUtil.getInstance(),
                idpTokenService);
    }

    @Bean
    public ProducerConfigService producerConfigService(
            ManagementNodeDataHandler managementNodeDataHandler) {
        return new ProducerConfigService(
                managementNodeDataHandler,
                InMemoryConfigurationStore.getInstance());
    }

    /**
     * OCSP verification service - used by DsiProductAuthorizationFilter (Stage 2).
     * Calls GET /api/v1/certificate/ocsp?clientId={consumerId} on Management Node.
     * Fixes gRPC OcspServerInterceptor bug: passes actual consumerId, not empty string.
     */
    @Bean
    public OcspVerificationService ocspVerificationService(IdpTokenService idpTokenService) {
        Properties props = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
        return new OcspVerificationServiceImpl(props, idpTokenService);
    }

    /**
     * Outbound client used by BackendProxyController to forward authorized requests
     * to the internal backend. The backend is authenticated with the shared API key,
     * not mTLS.
     *
     * <p>The gateway reaches the backend the SAME way it reaches the Vault service:
     * it validates the backend's certificate against the mounted DPN truststore
     * ({@code vault.truststore.path} / {@code vault.truststore.password} from the
     * common configuration — the {@code cert-manager-truststore} file), building a
     * trust-only {@link SSLContext} from it. This mirrors how the gRPC federator
     * trusts the OTel Collector's OTLP endpoint (its {@code OtlpTruststoreSupport}):
     * a mounted truststore read by path+password, no live Vault call and no
     * {@code VaultTlsSupport}. The truststore is loaded PKCS12-first with a JKS
     * fallback — exactly how {@code VaultClient} loads this same file to reach the
     * Vault service (the cert manager writes it as PKCS12 despite the {@code .jks}
     * name). No client certificate is presented (server-only TLS on the backend).
     *
     * <p>When no truststore path is configured (e.g. a plain local run with an HTTP
     * backend) a plain factory is returned and the gateway talks HTTP.
     */
    @Bean
    public RestTemplate backendRestTemplate() {
        int connectTimeoutMs = (int) Duration.ofSeconds(10).toMillis();
        int readTimeoutMs = (int) Duration.ofSeconds(60).toMillis();

        Properties props = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
        String truststorePath = props.getProperty(VAULT_TRUSTSTORE_PATH);
        String truststorePassword = props.getProperty(VAULT_TRUSTSTORE_PASSWORD);

        if (truststorePath == null || truststorePath.isBlank()) {
            log.info("{} not set — backend RestTemplate uses plain HTTP (no TLS trust material)",
                    VAULT_TRUSTSTORE_PATH);
            SimpleClientHttpRequestFactory plainFactory = new SimpleClientHttpRequestFactory();
            plainFactory.setConnectTimeout(connectTimeoutMs);
            plainFactory.setReadTimeout(readTimeoutMs);
            return new RestTemplate(plainFactory);
        }

        try {
            // Trust the backend via the mounted DPN truststore — the same truststore
            // used to reach the Vault service. No Vault call.
            KeyStore trustStore = loadTrustStore(truststorePath, truststorePassword);
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            log.info("Backend RestTemplate trusting the DPN truststore '{}' (same truststore used "
                    + "to reach the Vault service)", truststorePath);

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                        throws IOException {
                    if (connection instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(sslContext.getSocketFactory());
                        // The backend presents the common DPN node identity cert (the same
                        // certificate the Vault service presents) rather than one naming its
                        // own Kubernetes Service DNS name, so default hostname verification
                        // against e.g. "dpn-demo-runner-server-1" always fails even though the
                        // chain is trusted. The truststore above already establishes trust;
                        // skip the SAN/hostname match on top of it — the same posture the
                        // Vault client connection uses.
                        https.setHostnameVerifier((hostname, session) -> true);
                    }
                    super.prepareConnection(connection, httpMethod);
                }
            };
            requestFactory.setConnectTimeout(connectTimeoutMs);
            requestFactory.setReadTimeout(readTimeoutMs);
            return new RestTemplate(requestFactory);
        } catch (Exception e) {
            throw new FederatorSslException("Failed to build backend RestTemplate SSLContext", e);
        }
    }

    /**
     * Loads the DPN truststore. Despite the {@code .jks} filename convention, the
     * certificate manager writes this file in PKCS12 format, so PKCS12 is tried first
     * with a fall back to real JKS — mirroring how {@code VaultClient} loads the same
     * file to connect to the Vault service.
     */
    private static KeyStore loadTrustStore(String path, String password) throws Exception {
        char[] pw = password != null ? password.toCharArray() : null;
        for (String type : new String[] {"PKCS12", "JKS"}) {
            try (FileInputStream fis = new FileInputStream(path)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(fis, pw);
                return ks;
            } catch (Exception e) {
                log.debug("Could not load backend truststore '{}' as {}: {}", path, type, e.getMessage());
            }
        }
        throw new FederatorSslException(
                "Could not load backend truststore '" + path + "' as PKCS12 or JKS", null);
    }
}
