// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Properties;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.dsi.dpn.federator.rest.framework.server.proxy.BackendProperties;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.management.ManagementNodeDataHandler;
import org.dsi.dpn.common.service.config.ProducerConfigService;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.service.secret.VaultSslBundleRegistrar;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.dsi.dpn.common.storage.InMemoryConfigurationStore;
import org.dsi.dpn.common.utils.IdpTokenServiceFactory;
import org.dsi.dpn.common.utils.HttpClientFactoryUtils;
import org.dsi.dpn.common.utils.ObjectMapperUtil;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationService;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationServiceImpl;

/**
 * FRAMEWORK — do not modify.
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

    static {
        if (!PropertyUtil.initializeProperties()) {
            throw new IllegalStateException(
                "REST Federator Server: failed to initialise PropertyUtil. "
                + "Set FEDERATOR_SERVER_PROPERTIES env var to path of server.properties.");
        }
        log.info("PropertyUtil initialised for REST Federator Server");
    }

    /** Sources this gateway's own inbound {@code federator-tls} bundle from Vault. */
    @Bean
    public SslBundleRegistrar vaultSslBundleRegistrar() {
        return new VaultSslBundleRegistrar("federator-tls");
    }

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
     * OCSP verification service — used by DsiProductAuthorizationFilter (Stage 2).
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
     * not mTLS — but when it terminates HTTPS with the same Vault-issued certificate
     * this gateway uses, this client needs to trust that certificate's CA. No client
     * cert is presented here (server-only TLS on the backend's side).
     */
    @Bean
    public RestTemplate backendRestTemplate(RestTemplateBuilder builder) {
        RestTemplateBuilder configured = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60));

        if (!VaultTlsSupport.isVaultTlsEnabled()) {
            return configured.build();
        }

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, VaultTlsSupport.trustManagers(), null);

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                        throws IOException {
                    if (connection instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(sslContext.getSocketFactory());
                        // The backend presents the shared node identity cert (same Vault
                        // alias/SAN list as this gateway's own inbound cert) rather than one
                        // naming its own Kubernetes Service DNS name, so default hostname
                        // verification against e.g. "dpn-demo-runner-server-1" always fails
                        // even though the chain is trusted. The CA-based trustManagers above
                        // already establish trust; skip the SAN/hostname match on top of it.
                        https.setHostnameVerifier((hostname, session) -> true);
                    }
                    super.prepareConnection(connection, httpMethod);
                }
            };
            return configured.requestFactory(() -> requestFactory).build();
        } catch (Exception e) {
            throw new FederatorSslException("Failed to build backend RestTemplate SSLContext", e);
        }
    }
}
