// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import java.time.Duration;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.dsi.dpn.federator.rest.framework.server.proxy.BackendProperties;
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
     * to the internal backend. Plain HTTP — the backend sits inside the cluster and
     * is authenticated with the shared API key, not mTLS.
     */
    @Bean
    public RestTemplate backendRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
