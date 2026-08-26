// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package org.dsi.dpn.common.service.idp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Builds the JWKS document the gateway publishes so Keycloak can verify the
 * private_key_jwt client assertions {@link IdpTokenServicePrivateJwtImpl} signs.
 * <p>
 *     There is no pre-existing JWKS-serving code anywhere in this system (confirmed
 *     with the certificate manager team) — this is the first. The one convention that
 *     already exists and must be matched exactly is the {@code kid}: it has to equal
 *     {@link IdpTokenServicePrivateJwtImpl#deriveKidFromCertificate(X509Certificate)},
 *     the same RFC 7638/x5t#S256 SHA-256 certificate thumbprint the signing side already
 *     computes, so a signed assertion's {@code kid} header always resolves to an entry
 *     in this JWKS. {@code x5c} is included because Keycloak's own thumbprint check
 *     under "Use JWKS URL" is computed over {@code x5c[0]}, not over {@code n}/{@code e}
 *     alone.
 * </p>
 */
public final class JwksSupport {

    private JwksSupport() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /** Builds the single-key JWKS document for the given leaf certificate. */
    public static Map<String, Object> buildJwksDocument(X509Certificate leafCertificate) throws Exception {
        RSAKey parsed = RSAKey.parse(leafCertificate);
        String kid = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(leafCertificate);

        RSAKey jwk = new RSAKey.Builder(parsed)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(kid)
                .build();

        return new JWKSet(jwk).toJSONObject();
    }
}
