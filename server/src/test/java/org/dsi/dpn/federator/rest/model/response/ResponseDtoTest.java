// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.model.response;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for response DTO classes — exercises Lombok-generated
 * builders, getters, and constructors for JaCoCo coverage.
 */
class ResponseDtoTest {

    @Test @DisplayName("ErrorResponse builder and getters")
    void errorResponse() {
        ErrorResponse r = ErrorResponse.builder()
                .code("DUPLICATE_MPAN")
                .message("Duplicate MPANs detected")
                .conflictingMpans(List.of("1000000000001")).build();
        assertThat(r.getCode()).isEqualTo("DUPLICATE_MPAN");
        assertThat(r.getMessage()).isEqualTo("Duplicate MPANs detected");
        assertThat(r.getConflictingMpans()).containsExactly("1000000000001");
    }
}