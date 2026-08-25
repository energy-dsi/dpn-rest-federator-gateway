// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** registration block supplied on an FMAR001 registration request. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegistrationRequestDetail {

    @Schema(description = "Whether contractual authorisation has been obtained",
            example = "true")
    private Boolean contractualAuthorisationIndicator;

    @Schema(description = "Date the registration takes effect from",
            example = "2026-04-01")
    private LocalDate assetRegistrationEffectiveFromDate;
}
