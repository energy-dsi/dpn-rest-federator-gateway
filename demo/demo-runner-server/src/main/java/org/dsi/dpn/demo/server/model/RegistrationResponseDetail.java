// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** registration block returned on an FMAR002 response. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegistrationResponseDetail {

    @Schema(description = "Status of the asset registration", example = "Registered")
    private String assetRegistrationStatus;

    @Schema(description = "Data quality score for the registration", example = "95")
    private Integer assetRegistrationQualityScore;
}
