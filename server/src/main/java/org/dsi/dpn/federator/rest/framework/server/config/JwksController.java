// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.dsi.dpn.common.service.idp.JwksSupport;
import org.dsi.dpn.common.service.secret.SecretProvider;
import org.dsi.dpn.common.service.secret.VaultKeystoreProvider;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Publishes the gateway's own signing certificate as a JWKS document, so Keycloak
 * can validate the private_key_jwt client assertions {@code IdpTokenServicePrivateJwtImpl}
 * signs for this same identity (shared-identity model: one leaf cert, one Keycloak
 * client — {@code FEDERATOR_DPN01} — for both the producer and consumer roles).
 * <p>
 *     There is no existing JWKS-serving code anywhere else in this system to extend —
 *     confirmed with the certificate manager team — so this is the first. Built fresh
 *     on every request (this is a low-traffic, infrequently-polled endpoint) so a
 *     certificate rotation is picked up immediately, with no separate cache to
 *     invalidate.
 * </p>
 * <p>
 *     Exempted from JWT/OAuth2 and {@code DsiProductAuthorizationFilter} in
 *     {@link SecurityConfig} — Keycloak must be able to fetch this without first having
 *     a token, since the whole point is bootstrapping trust for future tokens.
 * </p>
 */
@RestController
@Slf4j
public class JwksController {

    private static final String COMMON_CONFIG = "common.configuration";

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() throws Exception {
        Properties common = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        String basePath = common.getProperty(
                VaultTlsSupport.VAULT_TLS_SECRET_BASE_PATH, VaultTlsSupport.DEFAULT_SECRET_BASE_PATH);

        X509Certificate leafCertificate = VaultKeystoreProvider.fetchLeafCertificate(provider, basePath);
        return JwksSupport.buildJwksDocument(leafCertificate);
    }
}
