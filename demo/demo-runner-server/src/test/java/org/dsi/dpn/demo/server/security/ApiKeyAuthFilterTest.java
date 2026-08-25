// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyAuthFilterTest {

    static final String KEY = "s3cret-key";

    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;
    @Mock FilterChain         chain;

    StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
        when(request.getRequestURI()).thenReturn("/assets");
        when(request.getMethod()).thenReturn("GET");
    }

    @Test @DisplayName("Valid API key passes through to the chain")
    void validKey_passes() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn(KEY);

        new ApiKeyAuthFilter(KEY).doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test @DisplayName("Missing API key is rejected with 401")
    void missingKey_rejected() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn(null);

        new ApiKeyAuthFilter(KEY).doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        assertThat(responseWriter.toString()).contains("UNAUTHORIZED");
    }

    @Test @DisplayName("Wrong API key is rejected with 401")
    void wrongKey_rejected() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn("not-the-key");

        new ApiKeyAuthFilter(KEY).doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test @DisplayName("No key configured runs open (local development)")
    void noKeyConfigured_runsOpen() throws Exception {
        new ApiKeyAuthFilter("").doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test @DisplayName("Actuator and Swagger paths bypass the filter")
    void openPaths_bypass() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(KEY);

        when(request.getRequestURI()).thenReturn("/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        when(request.getRequestURI()).thenReturn("/v3/api-docs");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        when(request.getRequestURI()).thenReturn("/assets");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
