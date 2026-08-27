// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.federator.rest.framework.server.ocsp;

/** Certificate revocation status returned by the Management Node OCSP endpoint. */
public enum OcspStatus {
    ACTIVE,    // Certificate is valid and active
    REVOKED,   // Certificate has been explicitly revoked
    EXPIRED,   // Certificate validity period has passed
    NOT_FOUND  // No certificate found for this clientId, or endpoint unreachable
}
