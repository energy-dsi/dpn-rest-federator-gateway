// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtConverterTest {

    private final KeycloakJwtConverter converter = new KeycloakJwtConverter();

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    @Test @DisplayName("extractClientId prefers the azp claim")
    void clientIdFromAzp() {
        Jwt jwt = jwt().claim("azp", "client-a").claim("client_id", "other").build();
        assertThat(converter.extractClientId(jwt)).isEqualTo("client-a");
    }

    @Test @DisplayName("extractClientId falls back to client_id when azp is absent/blank")
    void clientIdFromClientIdClaim() {
        Jwt jwt = jwt().claim("azp", " ").claim("client_id", "client-b").build();
        assertThat(converter.extractClientId(jwt)).isEqualTo("client-b");
    }

    @Test @DisplayName("extractClientId returns 'unknown' when neither claim is present")
    void clientIdUnknown() {
        Jwt jwt = jwt().claim("sub", "x").build();
        assertThat(converter.extractClientId(jwt)).isEqualTo("unknown");
    }

    @Test @DisplayName("convert produces a JwtAuthenticationToken named by the client id")
    void convertSetsName() {
        Jwt jwt = jwt().claim("azp", "client-a").build();
        AbstractAuthenticationToken token = converter.convert(jwt);
        assertThat(token).isNotNull();
        assertThat(token.getName()).isEqualTo("client-a");
    }

    @Test @DisplayName("convert maps resource_access roles to ROLE_<resource>:<role> authorities")
    void convertMapsResourceRoles() {
        Jwt jwt = jwt()
                .claim("azp", "client-a")
                .claim("resource_access", Map.of(
                        "my-api", Map.of("roles", List.of("reader", "writer"))))
                .build();
        AbstractAuthenticationToken token = converter.convert(jwt);
        assertThat(token.getAuthorities())
                .extracting("authority")
                .contains("ROLE_my-api:reader", "ROLE_my-api:writer");
    }

    @Test @DisplayName("convert tolerates a missing/blank resource_access claim")
    void convertNoResourceAccess() {
        Jwt jwt = jwt().claim("azp", "client-a").build();
        assertThat(converter.convert(jwt).getAuthorities()).isNotNull();
    }
}
