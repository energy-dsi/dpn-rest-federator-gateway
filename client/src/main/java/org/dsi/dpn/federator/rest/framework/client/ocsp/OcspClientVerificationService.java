// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.federator.rest.framework.client.ocsp;

/**
 * FRAMEWORK — client side.
 *
 * Verifies the revocation status of the producer DPN's certificate
 * before making any outbound REST call to that producer.
 *
 * Called by RestClient before each get()/post() call.
 * The clientId to check is the producer's idpClientId from ConsumerConfigDTO.
 */
public interface OcspClientVerificationService {
    /**
     * Checks whether the certificate for the given producer clientId is ACTIVE.
     *
     * @param producerClientId the producer's Keycloak client ID
     * @return OCSP status from Management Node
     */
    OcspStatus verify(String producerClientId);
}
