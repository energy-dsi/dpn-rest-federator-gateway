// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.federator.rest.framework.server.ocsp;

/**
 * FRAMEWORK — server side.
 *
 * Verifies the revocation status of an inbound consumer's certificate
 * by calling the Management Node OCSP endpoint.
 *
 * Called by DsiProductAuthorizationFilter after the consumer's clientId
 * has been extracted from the JWT azp claim — fixing the bug in the gRPC
 * OcspServerInterceptor which passed an empty string instead of the clientId.
 */
public interface OcspVerificationService {
    /**
     * Checks whether the certificate for the given clientId is ACTIVE.
     *
     * @param clientId the consumer's Keycloak client ID (from JWT azp claim)
     * @return OCSP status from Management Node
     */
    OcspStatus verify(String clientId);
}
