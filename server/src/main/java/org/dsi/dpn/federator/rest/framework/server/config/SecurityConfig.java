// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.dsi.dpn.federator.rest.framework.server.security.DsiProductAuthorizationFilter;
import org.dsi.dpn.federator.rest.framework.server.security.KeycloakJwtConverter;

/**
 * FRAMEWORK — do not modify.
 *
 * Spring Security filter chain for the REST Federator producer-side.
 *
 * Filter order (mirrors the gRPC interceptor chain):
 *   1. Spring OAuth2 JWT validation  — verifies Bearer token signature via Keycloak JWKS.
 *                                      Equivalent to AuthServerInterceptor.
 *   2. DsiProductAuthorizationFilter — consumer verification + product path authorization.
 *                                      Equivalent to ConsumerVerificationServerInterceptor
 *                                      plus REST-specific path matching.
 *   3. SecurityHeadersFilter         — OWASP security response headers.
 *
 * DsiProductAuthorizationFilter is anchored to BearerTokenAuthenticationFilter
 * (not UsernamePasswordAuthenticationFilter): the resource-server JWT filter
 * runs later in Spring Security's standard chain than the form-login filter,
 * so anchoring to the latter ran DsiProductAuthorizationFilter before
 * SecurityContextHolder had an Authentication — its "not yet authenticated"
 * early-return then let every request through unchecked, regardless of path.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final KeycloakJwtConverter           jwtConverter;
    private final DsiProductAuthorizationFilter  authFilter;
    private final SecurityHeadersFilter          headersFilter;

    public SecurityConfig(KeycloakJwtConverter jwtConverter,
                          DsiProductAuthorizationFilter authFilter,
                          SecurityHeadersFilter headersFilter) {
        this.jwtConverter  = jwtConverter;
        this.authFilter    = authFilter;
        this.headersFilter = headersFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/.well-known/**")
                .permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
            .addFilterAfter(authFilter, BearerTokenAuthenticationFilter.class)
            .addFilterAfter(headersFilter, DsiProductAuthorizationFilter.class);

        return http.build();
    }
}
