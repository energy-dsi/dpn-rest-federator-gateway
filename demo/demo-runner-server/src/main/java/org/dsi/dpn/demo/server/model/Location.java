// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** location block of an FMAR asset. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Location {

    @Schema(description = "Whether the asset is at domestic premises", example = "true")
    private Boolean domesticPremisesIndicator;

    @Schema(description = "Postcode of the asset location", example = "SW1A 1AA")
    private String postcode;

    @Schema(description = "Latitude", example = "51.5014")
    private Double latitude;

    @Schema(description = "Longitude", example = "-0.1419")
    private Double longitude;
}
