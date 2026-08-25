// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * network block of an FMAR asset.
 *
 * <p>MPANs are carried as strings, not numbers: a 13-digit MPAN core exceeds the
 * precision JavaScript/JSON numbers handle safely, so treating them as numeric
 * risks silent corruption of the trailing digits.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Network {

    @Schema(description = "Import MPAN cores (13 digits each)",
            example = "[\"1000000000001\"]")
    private List<String> importMpans;

    @Schema(description = "GSP group identifier", example = "_A")
    private String gspGroupId;

    @Schema(description = "Connection voltage", example = "LV")
    private String connectionVoltage;
}
