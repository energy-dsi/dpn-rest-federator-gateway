// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.client;

import java.util.UUID;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Inputs for a demo run.
 *
 * <p>Resolution order for each value: environment variable, then the matching
 * property in {@code client.properties}, then a built-in default. Environment
 * variables come first because that is how a Kubernetes Job supplies them — the
 * Job's {@code env:} block is templated from Helm values, which the CD pipeline
 * sets per run (see charts/dpn-demo-runner-client).
 *
 * <p>{@code ORGANISATION_NAME} and {@code PRODUCT_NAME} are mandatory: together they identify the
 * DSM data product, and the rest-federator-client resolves the producer host, internal producer id
 * and credentials from them. The producer id is never supplied here — it is internal to the
 * Management Node and the client learns it from getConsumerConfig.
 */
public record DemoParameters(
        String organisation,
        String productName,
        String importMpan,
        String postcode,
        UUID assetId,
        UUID fspId,
        String senderFmarId,
        String senderRole,
        boolean contractualAuthorisation) {

    private static final String DEFAULT_MPAN     = "1000000000001";
    private static final String DEFAULT_POSTCODE = "SW1A 1AA";
    private static final String DEFAULT_ROLE     = "FSP";

    /**
     * Reads parameters from the environment, falling back to client.properties
     * and then to demo defaults.
     */
    public static DemoParameters resolve() {
        // ORGANISATION_NAME and PRODUCT_NAME may each be a single value, a comma-separated list, or
        // blank. They filter the client's subscription registry to the targets to run against
        // (see FmarDemoRunner.resolveTargets) — blank/blank targets every subscribed product.
        String organisation = resolveValue("ORGANISATION_NAME", "federator.rest.organisation", "");
        String productName = resolveValue("PRODUCT_NAME", "federator.rest.product.name", "");

        return new DemoParameters(
                organisation,
                productName,
                resolveValue("MPAN", "federator.rest.demo.mpan", DEFAULT_MPAN),
                resolveValue("POSTCODE", "federator.rest.demo.postcode", DEFAULT_POSTCODE),
                optionalUuid(resolveValue("ASSET_ID", "federator.rest.demo.asset.id", "")),
                resolveFspId(),
                resolveValue("SENDER_FMAR_ID", "federator.rest.demo.sender.id", productName),
                resolveValue("SENDER_ROLE", "federator.rest.demo.sender.role", DEFAULT_ROLE),
                Boolean.parseBoolean(resolveValue(
                        "CONTRACTUAL_AUTHORISATION",
                        "federator.rest.demo.contractual.authorisation", "true")));
    }

    /** A random FSP id keeps repeat demo runs from colliding when none is supplied. */
    private static UUID resolveFspId() {
        String configured = resolveValue("FSP_ID", "federator.rest.demo.fsp.id", "");
        return configured.isBlank() ? UUID.randomUUID() : parseUuid("FSP_ID", configured);
    }

    private static UUID optionalUuid(String value) {
        return value.isBlank() ? null : parseUuid("ASSET_ID", value);
    }

    private static UUID parseUuid(String name, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    name + " must be a valid UUID, or blank ("
                            + (name.equals("FSP_ID") ? "a random one is generated" : "it is omitted")
                            + ") -- got '" + value + "'", e);
        }
    }

    private static String resolveValue(String envVar, String propertyKey, String defaultValue) {
        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        try {
            String fromProps = PropertyUtil.getPropertyValue(propertyKey, defaultValue);
            return fromProps != null ? fromProps.trim() : defaultValue;
        } catch (Exception e) {
            // PropertyUtil not initialised, or key absent — fall back to the default.
            return defaultValue;
        }
    }
}
