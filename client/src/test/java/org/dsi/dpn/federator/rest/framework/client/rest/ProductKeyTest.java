// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductKey} - previously entirely untested.
 */
class ProductKeyTest {

    @Test
    @DisplayName("Constructing with valid organisation and productName succeeds")
    void constructor_validValues_succeeds() {
        ProductKey key = new ProductKey("Acme Energy", "FMAR Asset Registration");

        assertThat(key.organisation()).isEqualTo("Acme Energy");
        assertThat(key.productName()).isEqualTo("FMAR Asset Registration");
    }

    @Test
    @DisplayName("Constructor trims leading/trailing whitespace from organisation")
    void constructor_organisationWithWhitespace_trimsIt() {
        ProductKey key = new ProductKey("  Acme Energy  ", "FMAR Asset Registration");

        assertThat(key.organisation()).isEqualTo("Acme Energy");
    }

    @Test
    @DisplayName("Constructor trims leading/trailing whitespace from productName")
    void constructor_productNameWithWhitespace_trimsIt() {
        ProductKey key = new ProductKey("Acme Energy", "  FMAR Asset Registration  ");

        assertThat(key.productName()).isEqualTo("FMAR Asset Registration");
    }

    @Test
    @DisplayName("Constructor rejects a null organisation, naming the field in the exception message")
    void constructor_nullOrganisation_throwsNamingField() {
        assertThatThrownBy(() -> new ProductKey(null, "FMAR Asset Registration"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProductKey.organisation is required");
    }

    @Test
    @DisplayName("Constructor rejects a blank organisation")
    void constructor_blankOrganisation_throws() {
        assertThatThrownBy(() -> new ProductKey("   ", "FMAR Asset Registration"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProductKey.organisation is required");
    }

    @Test
    @DisplayName("Constructor rejects a null productName, naming the field in the exception message")
    void constructor_nullProductName_throwsNamingField() {
        assertThatThrownBy(() -> new ProductKey("Acme Energy", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProductKey.productName is required");
    }

    @Test
    @DisplayName("Constructor rejects a blank productName")
    void constructor_blankProductName_throws() {
        assertThatThrownBy(() -> new ProductKey("Acme Energy", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProductKey.productName is required");
    }

    @Test
    @DisplayName("The validation error explicitly mentions the producerId must not be supplied")
    void constructor_errorMessage_mentionsProducerIdNotRequired() {
        assertThatThrownBy(() -> new ProductKey("", "FMAR Asset Registration"))
                .hasMessageContaining("producerId is resolved internally and must not be supplied");
    }

    @Test
    @DisplayName("toString() renders both fields in the custom 'organisation=..., productName=...' format, "
            + "not the default record format")
    void toString_rendersCustomFormat() {
        ProductKey key = new ProductKey("Acme Energy", "FMAR Asset Registration");

        assertThat(key.toString()).isEqualTo("organisation='Acme Energy', productName='FMAR Asset Registration'");
    }

    @Test
    @DisplayName("Two ProductKeys with the same organisation and productName are equal, per record semantics")
    void equals_sameValues_areEqual() {
        ProductKey first = new ProductKey("Acme Energy", "FMAR Asset Registration");
        ProductKey second = new ProductKey("Acme Energy", "FMAR Asset Registration");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    @DisplayName("Two ProductKeys that only differ by pre-trim whitespace are still equal, since both "
            + "trim to the same value")
    void equals_differOnlyByWhitespace_areEqual() {
        ProductKey first = new ProductKey("Acme Energy", "FMAR Asset Registration");
        ProductKey second = new ProductKey("  Acme Energy  ", "  FMAR Asset Registration  ");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Two ProductKeys with different organisations are not equal")
    void equals_differentOrganisation_areNotEqual() {
        ProductKey first = new ProductKey("Acme Energy", "FMAR Asset Registration");
        ProductKey second = new ProductKey("Different Org", "FMAR Asset Registration");

        assertThat(first).isNotEqualTo(second);
    }
}
