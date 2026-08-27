// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dsi.dpn.demo.server.model.AssetRegistrationResponse;
import org.dsi.dpn.demo.server.model.NewAssetRegistrationRequest;
import org.dsi.dpn.demo.server.model.SenderRole;
import org.dsi.dpn.demo.server.store.InMemoryAssetStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXAMPLE BACKEND — implements a subset of the MHHS FMAR specification.
 *
 * <p>Endpoints (parameters and headers follow the published spec; paths carry an
 * {@code /api/v1/fmar} prefix for consistency with this gateway's other routes —
 * the spec itself defines the bare {@code /assets} and {@code /fsp/{fspId}/assets}):
 * <ul>
 *   <li>{@code GET  /api/v1/fmar/assets}             — look up an asset by import MPAN</li>
 *   <li>{@code POST /api/v1/fmar/fsp/{fspId}/assets} — register a new asset</li>
 * </ul>
 *
 * <p>This service is reached through the REST Federator gateway, which has already
 * performed mTLS, JWT, OCSP and method+path authorisation. Its own authentication
 * is the shared API key checked by ApiKeyAuthFilter.
 *
 * @see <a href="https://api.swaggerhub.com/apis/MHHSPROGRAMME/fmar-specification-api-0.5draft/0.5.0-draft">FMAR specification</a>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "FMAR Assets",
        description = "Asset query and registration — example implementation of the "
                + "MHHS FMAR specification.")
public class FmarAssetController {

    private final InMemoryAssetStore assetStore;

    @GetMapping(value = "/api/v1/fmar/assets", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Query an asset by import MPAN",
            description = "Returns the registered asset matching the supplied import MPAN "
                    + "(and postcode/assetId when provided).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset found"),
            @ApiResponse(responseCode = "400", description = "Invalid or missing parameters"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
            @ApiResponse(responseCode = "404", description = "No asset found")
    })
    public ResponseEntity<AssetRegistrationResponse> getAsset(

            @Parameter(in = ParameterIn.QUERY, required = true,
                    description = "13-digit MPAN core", example = "1000000000001")
            @RequestParam
            @NotNull
            @Pattern(regexp = "^\\d{13}$", message = "importMpan must be exactly 13 digits")
            String importMpan,

            @Parameter(in = ParameterIn.QUERY, required = true,
                    description = "Postcode of the asset location", example = "SW1A 1AA")
            @RequestParam
            @NotNull
            @Size(max = 10, message = "postcode must be at most 10 characters")
            String postcode,

            @Parameter(in = ParameterIn.QUERY,
                    description = "Optional asset identifier to narrow the query")
            @RequestParam(required = false)
            UUID assetId,

            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "Identifier of the sending party")
            @RequestHeader("X-Sender-FMAR-Id") String senderFmarId,

            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "Role of the sending party")
            @RequestHeader("X-Sender-Role") SenderRole senderRole,

            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "Whether contractual authorisation is held")
            @RequestHeader("X-Contractual-Authorisation") boolean contractualAuthorisation) {

        log.info("GET /api/v1/fmar/assets importMpan={} postcode={} assetId={} sender={}/{} auth={}",
                importMpan, postcode, assetId, senderFmarId, senderRole,
                contractualAuthorisation);

        return assetStore.find(importMpan, postcode, assetId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.info("GET /api/v1/fmar/assets — no asset found for importMpan={}", importMpan);
                    return ResponseEntity.notFound().build();
                });
    }

    @PostMapping(value = "/api/v1/fmar/fsp/{fspId}/assets",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a new asset",
            description = "Registers a new asset for the given FSP and returns the "
                    + "system-generated FMARAssetIdentifier.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset registered"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
            @ApiResponse(responseCode = "409", description = "Import MPAN already registered")
    })
    public ResponseEntity<AssetRegistrationResponse> registerAsset(

            @Parameter(in = ParameterIn.PATH, required = true,
                    description = "Identifier of the registering FSP")
            @PathVariable UUID fspId,

            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "Identifier of the sending party")
            @RequestHeader("X-Sender-FMAR-Id") String senderFmarId,

            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "Role of the sending party")
            @RequestHeader("X-Sender-Role") SenderRole senderRole,

            @Valid @RequestBody NewAssetRegistrationRequest request) {

        log.info("POST /api/v1/fmar/fsp/{}/assets assetName='{}' sender={}/{}",
                fspId, request.getAssetName(), senderFmarId, senderRole);

        // The spec restricts registration to these two statuses; the wider set is
        // only valid on a query response.
        if (request.getAssetStatus() != null
                && !request.getAssetStatus().isValidForRegistration()) {
            throw new IllegalArgumentException(
                    "assetStatus '" + request.getAssetStatus().getWireValue()
                            + "' is not permitted on registration — use "
                            + "'Awaiting Energisation' or 'Energised'.");
        }

        AssetRegistrationResponse response = assetStore.register(request, fspId);
        return ResponseEntity.ok(response);
    }
}
