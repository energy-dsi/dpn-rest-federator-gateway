// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FMAR002_AssetRegistrationRequestResponse — returned by both
 * {@code GET /assets} and {@code POST /fsp/{fspId}/assets}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "FMAR002_AssetRegistrationRequestResponse",
        description = "Asset registration/query response")
public class AssetRegistrationResponse {

    /**
     * Spec field name is upper-camel ("FMARAssetIdentifier"), so it is mapped
     * explicitly rather than relying on Java naming conventions.
     */
    @JsonProperty("FMARAssetIdentifier")
    @Schema(description = "System-generated asset identifier",
            example = "<<your specific value>>")
    private UUID fmarAssetIdentifier;

    @Schema(description = "Name of the asset", example = "Battery Unit 1")
    private String assetName;

    @Schema(description = "Installed capacity in MW", example = "2.5")
    private Double installedCapacity;

    @Schema(description = "Whether the asset generates or stores energy", example = "true")
    private Boolean generationStorageIndicator;

    @Schema(description = "Whether the asset presents demand", example = "false")
    private Boolean demandIndicator;

    @Schema(description = "Asset status", example = "Energised")
    private AssetStatus assetStatus;

    @Schema(description = "Energy conversion types")
    private List<String> energyConversionTypes;

    @Schema(description = "Energy source types")
    private List<String> energySourceTypes;

    @Schema(description = "Demand technology types")
    private List<String> demandTechnologyTypes;

    @Schema(description = "Network details")
    private Network network;

    @Schema(description = "Location details")
    private Location location;

    @Schema(description = "Metering arrangement details")
    private Metering metering;

    @Schema(description = "Registration status details")
    private RegistrationResponseDetail registration;
}
