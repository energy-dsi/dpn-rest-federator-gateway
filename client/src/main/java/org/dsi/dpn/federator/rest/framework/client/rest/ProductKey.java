// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package org.dsi.dpn.federator.rest.framework.client.rest;

/**
 * The caller-facing identity of a REST data product a consumer wants to call: the producing
 * {@code organisation} plus the {@code productName}.
 *
 * <p>These are the two values a participant actually knows — the human-readable labels DSM shows
 * for a data product. An integrator only has to supply these; they do <em>not</em> need to know the
 * producer's internal Keycloak client id ({@code producerId}/{@code idpClientId}), which is private
 * to the Management Node. The {@code RestClient} learns the {@code producerId} (and the base URL and
 * allowed paths) itself from {@code getConsumerConfig} at bootstrap and uses it internally for
 * routing and OCSP — it is never something the caller has to pass.
 *
 * <p>Both fields are required and are matched exactly against the products the consumer is
 * subscribed to. They come from the consumer's own configuration, so an integrator reads them from
 * {@code getConsumerConfig} (or is told them by the DPN operator) rather than guessing. Example:
 *
 * <pre>{@code
 * ProductKey key = new ProductKey("Acme Energy", "FMAR Asset Registration");
 * String body = restClient.get(key, "/api/v1/fmar/assets?importMpan=...");
 * }</pre>
 *
 * <p>Selecting the product is a routing concern only — it decides which producer's base URL and
 * mTLS client the call goes to. Authorisation (is this consumer allowed this method+path) is
 * always enforced independently by the gateway from the verified JWT and DSM config, never from
 * anything supplied here.
 *
 * @param organisation the producing organisation's name ({@code ProducerDTO.name}); required
 * @param productName  the data product's name ({@code ProductDTO.name}); required
 */
public record ProductKey(String organisation, String productName) {

    public ProductKey {
        organisation = requireText(organisation, "organisation");
        productName = requireText(productName, "productName");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "ProductKey." + field + " is required — address a REST product by its "
                            + "organisation and productName (both from getConsumerConfig). The "
                            + "producerId is resolved internally and must not be supplied.");
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "organisation='" + organisation + "', productName='" + productName + "'";
    }
}
