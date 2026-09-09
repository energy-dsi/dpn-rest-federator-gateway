// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.dsi.dpn.demo.server.model.AssetRegistrationResponse;
import org.dsi.dpn.demo.server.model.NewAssetRegistrationRequest;
import org.dsi.dpn.demo.server.model.RegistrationResponseDetail;
import org.springframework.stereotype.Component;

/**
 * EXAMPLE ONLY - in-memory asset store.
 *
 * <p>Data is not persisted and is lost on restart. A real participant would
 * replace this with their own data source; the REST Federator gateway is
 * unaffected either way since it never inspects the payload.
 */
@Component
@Slf4j
public class InMemoryAssetStore {

    /** importMpan -> stored asset. */
    private final Map<String, AssetRegistrationResponse> byMpan = new ConcurrentHashMap<>();
    /** FMARAssetIdentifier -> stored asset. */
    private final Map<UUID, AssetRegistrationResponse> byId = new ConcurrentHashMap<>();

    public InMemoryAssetStore() {
        log.warn("[EXAMPLE] InMemoryAssetStore active - data will NOT persist across restarts.");
    }

    /**
     * Registers a new asset and returns the stored representation.
     *
     * @throws DuplicateMpanException if any import MPAN is already registered
     */
    public AssetRegistrationResponse register(NewAssetRegistrationRequest request, UUID fspId) {
        List<String> mpans = request.getNetwork() != null
                && request.getNetwork().getImportMpans() != null
                ? request.getNetwork().getImportMpans()
                : List.of();

        List<String> duplicates = mpans.stream().filter(byMpan::containsKey).toList();
        if (!duplicates.isEmpty()) {
            throw new DuplicateMpanException(duplicates);
        }

        UUID assetId = UUID.randomUUID();
        AssetRegistrationResponse stored = AssetRegistrationResponse.builder()
                .fmarAssetIdentifier(assetId)
                .assetName(request.getAssetName())
                .installedCapacity(request.getInstalledCapacity())
                .generationStorageIndicator(request.getGenerationStorageIndicator())
                .demandIndicator(request.getDemandIndicator())
                .assetStatus(request.getAssetStatus())
                .energyConversionTypes(request.getEnergyConversionTypes())
                .energySourceTypes(request.getEnergySourceTypes())
                .demandTechnologyTypes(request.getDemandTechnologyTypes())
                .network(request.getNetwork())
                .location(request.getLocation())
                .metering(request.getMetering())
                .registration(RegistrationResponseDetail.builder()
                        .assetRegistrationStatus("Registered")
                        .assetRegistrationQualityScore(qualityScore(request))
                        .build())
                .build();

        byId.put(assetId, stored);
        mpans.forEach(mpan -> byMpan.put(mpan, stored));

        log.info("[EXAMPLE] Asset registered: FMARAssetIdentifier={} fspId={} mpans={}",
                assetId, fspId, mpans);
        return stored;
    }

    /**
     * Looks up an asset by import MPAN, optionally further constrained by postcode
     * and assetId. Returns empty when nothing matches all supplied criteria.
     */
    public Optional<AssetRegistrationResponse> find(String importMpan, String postcode,
                                                    UUID assetId) {
        AssetRegistrationResponse found = byMpan.get(importMpan);
        if (found == null) return Optional.empty();

        if (postcode != null && found.getLocation() != null
                && found.getLocation().getPostcode() != null
                && !postcode.equalsIgnoreCase(found.getLocation().getPostcode())) {
            log.debug("[EXAMPLE] MPAN {} found but postcode mismatch", importMpan);
            return Optional.empty();
        }
        if (assetId != null && !assetId.equals(found.getFmarAssetIdentifier())) {
            log.debug("[EXAMPLE] MPAN {} found but assetId mismatch", importMpan);
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /** Crude illustrative score - rewards optional fields being populated. */
    private int qualityScore(NewAssetRegistrationRequest request) {
        int score = 60;
        if (request.getMetering() != null) score += 10;
        if (request.getLocation() != null && request.getLocation().getLatitude() != null) score += 10;
        if (request.getEnergySourceTypes() != null
                && !request.getEnergySourceTypes().isEmpty()) score += 10;
        if (request.getRegistration() != null
                && Boolean.TRUE.equals(
                        request.getRegistration().getContractualAuthorisationIndicator())) {
            score += 10;
        }
        return score;
    }

    /** Raised when a submitted import MPAN is already registered. */
    public static class DuplicateMpanException extends RuntimeException {
        private final List<String> conflictingMpans;

        public DuplicateMpanException(List<String> conflictingMpans) {
            super("Import MPANs already registered: " + conflictingMpans);
            this.conflictingMpans = conflictingMpans;
        }

        public List<String> getConflictingMpans() {
            return conflictingMpans;
        }
    }
}
