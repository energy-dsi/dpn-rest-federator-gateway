// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

/**
 * FRAMEWORK — do not modify.
 *
 * Converts a Keycloak JWT into a Spring Security authentication token.
 * Extracts the consumer's Keycloak client ID from the {@code azp} claim
 * (same claim used by ConsumerVerificationServerInterceptor in the gRPC Federator).
 */
@Component
@Slf4j
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String AZP               = "azp";
    private static final String CLIENT_ID         = "client_id";
    private static final String RESOURCE_ACCESS   = "resource_access";
    private static final String ROLES             = "roles";
    private static final String ROLE_PREFIX       = "ROLE_";
    private static final String ROLE_SEP          = ":";
    private static final String UNKNOWN           = "unknown";

    private final JwtGrantedAuthoritiesConverter defaultConverter =
            new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String clientId = extractClientId(jwt);
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        log.debug("JWT converted: clientId={}", clientId);
        return new JwtAuthenticationToken(jwt, authorities, clientId);
    }

    /** Extracts azp (authorised party) — same field used by gRPC ConsumerVerificationServerInterceptor. */
    public String extractClientId(Jwt jwt) {
        String azp = jwt.getClaimAsString(AZP);
        if (azp != null && !azp.isBlank()) return azp;
        String direct = jwt.getClaimAsString(CLIENT_ID);
        if (direct != null && !direct.isBlank()) return direct;
        return UNKNOWN;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities =
                new ArrayList<>(defaultConverter.convert(jwt));
        Object ra = jwt.getClaim(RESOURCE_ACCESS);
        if (ra instanceof Map<?, ?> resourceAccess) {
            resourceAccess.forEach((resource, dataObj) -> {
                if (dataObj instanceof Map<?, ?> data) {
                    Object roleList = data.get(ROLES);
                    if (roleList instanceof Collection<?> roles) {
                        roles.forEach(role -> authorities.add(
                            new SimpleGrantedAuthority(
                                ROLE_PREFIX + resource + ROLE_SEP + role)));
                    }
                }
            });
        }
        return authorities;
    }
}
