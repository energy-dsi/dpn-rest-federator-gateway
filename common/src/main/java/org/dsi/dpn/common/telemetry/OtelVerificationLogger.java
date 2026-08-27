// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Emits an OTel-attribute-bearing structured log for a certificate/OCSP verification attempt,
 * MDC-scoped so the attributes only apply to the one log statement rather than leaking into
 * unrelated subsequent logs on the same thread.
 *
 * <p>Ported from dpn-federator's {@code OtelCertificateVerificationLogger}, generalized so both
 * the gateway's own consumer-verification path and the client library's producer-verification
 * path can share one implementation instead of duplicating it. Unlike the reference, this logs
 * exactly once per call (the reference logs twice on its exception path).
 */
public final class OtelVerificationLogger {

    private static final String ATTR_CLIENT_ID = "dpn.certificate.client_id";
    private static final String ATTR_TIMESTAMP = "dpn.certificate.verification_timestamp";
    private static final String ATTR_STATUS = "dpn.certificate.verification_status";

    private OtelVerificationLogger() {}

    /**
     * @param log       the caller's own logger, so the log line attributes to the right class
     * @param clientId  the identity (consumer or producer) whose certificate status was checked
     * @param timestamp when the check completed
     * @param active    whether the certificate status resolved as active
     * @param statusName the raw status value (e.g. ACTIVE, EXPIRED, REVOKED, NOT_FOUND)
     */
    public static void log(Logger log, String clientId, Instant timestamp, boolean active, String statusName) {
        try {
            MDC.put(ATTR_CLIENT_ID, clientId);
            MDC.put(ATTR_TIMESTAMP, timestamp.toString());
            MDC.put(ATTR_STATUS, statusName);

            if (active) {
                log.info("OCSP certificate verification: status={} clientId={} timestamp={}",
                        statusName, clientId, timestamp);
            } else {
                log.error("403 Forbidden — call blocked. OCSP certificate verification: status={} "
                        + "clientId={} timestamp={}", statusName, clientId, timestamp);
            }
        } finally {
            MDC.remove(ATTR_CLIENT_ID);
            MDC.remove(ATTR_TIMESTAMP);
            MDC.remove(ATTR_STATUS);
        }
    }
}
