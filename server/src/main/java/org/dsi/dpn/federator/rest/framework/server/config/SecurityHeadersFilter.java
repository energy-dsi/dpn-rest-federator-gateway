// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FRAMEWORK — do not modify.
 *
 * Adds OWASP-recommended security headers to every HTTP response.
 * Applied after authentication so even open paths receive the headers.
 *
 * Headers:
 *   X-Content-Type-Options: nosniff      — prevent MIME-type sniffing
 *   X-Frame-Options: DENY                — prevent clickjacking
 *   Strict-Transport-Security            — enforce HTTPS (1 year)
 *   Cache-Control: no-store              — prevent caching of sensitive responses
 *   X-XSS-Protection: 0                 — disable legacy XSS filter (CSP preferred)
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-XSS-Protection", "0");
        chain.doFilter(req, res);
    }
}
