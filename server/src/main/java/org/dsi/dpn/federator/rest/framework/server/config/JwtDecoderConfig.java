// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import java.util.Properties;

import javax.net.ssl.SSLContext;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.SSLUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;

/**
 * Overrides Spring Boot's auto-configured JwtDecoder.
 *
 * WHY: the default NimbusJwtDecoder fetches the Keycloak JWKS
 * (spring.security.oauth2.resourceserver.jwt.jwk-set-uri) with a plain
 * RestTemplate that trusts only the JVM's default cacerts. In this stack
 * Keycloak presents a certificate issued by the local dev CA, which lives in
 * the federator truststore (idp.truststore.path / idp.truststore.password in
 * common.configuration) — NOT in cacerts. Result: "PKIX path building failed"
 * on every token validation and a blanket 401.
 *
 * This bean fetches the JWKS through an SSLContext built by
 * SSLUtils.createSSLContextWithTrustStore(commonProps) — the same trust
 * anchors used by the OCSP service and Management Node calls — so all
 * outbound HTTPS in the gateway trusts one consistent CA set.
 */
@Configuration
@Slf4j
public class JwtDecoderConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Bean
    public JwtDecoder jwtDecoder() {
        // Same loading pattern as RestFederatorServerConfig beans:
        // resolve the common-configuration file referenced by the
        // 'common.configuration' property.
        Properties commonProps = PropertyUtil.getPropertiesFromFilePath("common.configuration");

        // Build an mTLS-CAPABLE context (key managers + trust managers), mirroring
        // HttpClientFactoryUtils.createHttpClientWithMtls which the token-endpoint
        // and Management Node calls use. Keycloak's JWKS endpoint normally needs
        // server-auth TLS only, but if the deployment enforces TLS client auth
        // (ssl.client.auth=required / mTLS ingress) this still works; the key
        // manager is simply unused when the server doesn't request a certificate.
        //
        // Same switch HttpClientFactoryUtils.createHttpClientWithMtls() uses: when
        // vault.tls.enabled=true there is no keystore/truststore file on disk — the
        // cert manager's material lives only in Vault.
        SSLContext sslContext;
        try {
            if (org.dsi.dpn.common.service.secret.VaultTlsSupport.isVaultTlsEnabled()) {
                sslContext = org.dsi.dpn.common.service.secret.VaultTlsSupport.sslContext();
            } else {
                sslContext = SSLUtils.createSSLContext(
                        commonProps.getProperty("idp.keystore.path"),
                        commonProps.getProperty("idp.keystore.password"),
                        commonProps.getProperty("idp.truststore.path"),
                        commonProps.getProperty("idp.truststore.password"));
            }
        } catch (Exception e) {
            throw new org.dsi.dpn.common.exception.FederatorSslException("Failed to build JwtDecoder SSLContext", e);
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                if (connection instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(sslContext.getSocketFactory());
                }
                super.prepareConnection(connection, httpMethod);
            }
        };

        RestTemplate restTemplate = new RestTemplate(requestFactory);

        log.info("JwtDecoder configured with federator truststore for JWKS at '{}'",
                StringUtils.defaultIfBlank(jwkSetUri, "<unset>"));

        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(restTemplate)
                .build();
    }
}