// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FMAR001_NewAssetRegistrationRequest — body of {@code POST /fsp/{fspId}/assets}.
 *
 * <p>FMARAssetIdentifier is deliberately absent: it is system-generated and only
 * appears on the response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "FMAR001_NewAssetRegistrationRequest",
        description = "New asset registration request")
public class NewAssetRegistrationRequest {

    @NotBlank
    @Size(max = 40)
    @Schema(description = "Name of the asset", example = "Battery Unit 1", maxLength = 40)
    private String assetName;

    @NotNull
    @Positive
    @Schema(description = "Installed capacity in MW", example = "2.5")
    private Double installedCapacity;

    @NotNull
    @Schema(description = "Whether the asset generates or stores energy", example = "true")
    private Boolean generationStorageIndicator;

    @NotNull
    @Schema(description = "Whether the asset presents demand", example = "false")
    private Boolean demandIndicator;

    @NotNull
    @Schema(description = "Asset status — registration accepts only "
            + "'Awaiting Energisation' or 'Energised'",
            example = "Energised")
    private AssetStatus assetStatus;

    @Schema(description = "Energy conversion types (conditional on indicators)")
    private List<String> energyConversionTypes;

    @Schema(description = "Energy source types (conditional on indicators)")
    private List<String> energySourceTypes;

    @Schema(description = "Demand technology types (conditional on indicators)")
    private List<String> demandTechnologyTypes;

    @NotNull
    @Schema(description = "Network details, including import MPANs")
    private Network network;

    @NotNull
    @Schema(description = "Location details")
    private Location location;

    @Schema(description = "Metering arrangement details")
    private Metering metering;

    @Schema(description = "Registration details")
    private RegistrationRequestDetail registration;
}
