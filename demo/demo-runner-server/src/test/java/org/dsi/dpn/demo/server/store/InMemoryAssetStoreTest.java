// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.server.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.dsi.dpn.demo.server.model.AssetRegistrationResponse;
import org.dsi.dpn.demo.server.model.AssetStatus;
import org.dsi.dpn.demo.server.model.Location;
import org.dsi.dpn.demo.server.model.Network;
import org.dsi.dpn.demo.server.model.NewAssetRegistrationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryAssetStoreTest {

    static final String MPAN     = "1000000000001";
    static final String POSTCODE = "SW1A 1AA";

    InMemoryAssetStore store;
    UUID fspId;

    @BeforeEach
    void setUp() {
        store = new InMemoryAssetStore();
        fspId = UUID.randomUUID();
    }

    NewAssetRegistrationRequest request(String mpan, String postcode) {
        return NewAssetRegistrationRequest.builder()
                .assetName("Battery Unit 1")
                .installedCapacity(2.5)
                .generationStorageIndicator(true)
                .demandIndicator(false)
                .assetStatus(AssetStatus.ENERGISED)
                .network(Network.builder().importMpans(List.of(mpan)).gspGroupId("_A").build())
                .location(Location.builder().postcode(postcode)
                        .domesticPremisesIndicator(false).build())
                .build();
    }

    @Test @DisplayName("register() assigns an FMARAssetIdentifier and echoes the request")
    void register_assignsIdentifier() {
        AssetRegistrationResponse resp = store.register(request(MPAN, POSTCODE), fspId);

        assertThat(resp.getFmarAssetIdentifier()).isNotNull();
        assertThat(resp.getAssetName()).isEqualTo("Battery Unit 1");
        assertThat(resp.getInstalledCapacity()).isEqualTo(2.5);
        assertThat(resp.getAssetStatus()).isEqualTo(AssetStatus.ENERGISED);
        assertThat(resp.getRegistration().getAssetRegistrationStatus()).isEqualTo("Registered");
    }

    @Test @DisplayName("find() locates a registered asset by MPAN and postcode")
    void find_afterRegister() {
        AssetRegistrationResponse created = store.register(request(MPAN, POSTCODE), fspId);

        Optional<AssetRegistrationResponse> found = store.find(MPAN, POSTCODE, null);

        assertThat(found).isPresent();
        assertThat(found.get().getFmarAssetIdentifier())
                .isEqualTo(created.getFmarAssetIdentifier());
    }

    @Test @DisplayName("find() returns empty for an unknown MPAN")
    void find_unknownMpan() {
        assertThat(store.find("9999999999999", POSTCODE, null)).isEmpty();
    }

    @Test @DisplayName("find() returns empty when the postcode does not match")
    void find_postcodeMismatch() {
        store.register(request(MPAN, POSTCODE), fspId);
        assertThat(store.find(MPAN, "XX1 1XX", null)).isEmpty();
    }

    @Test @DisplayName("find() narrows by assetId when supplied")
    void find_assetIdFilter() {
        AssetRegistrationResponse created = store.register(request(MPAN, POSTCODE), fspId);

        assertThat(store.find(MPAN, POSTCODE, created.getFmarAssetIdentifier())).isPresent();
        assertThat(store.find(MPAN, POSTCODE, UUID.randomUUID())).isEmpty();
    }

    @Test @DisplayName("register() rejects an already-registered import MPAN")
    void register_duplicateMpan() {
        store.register(request(MPAN, POSTCODE), fspId);

        InMemoryAssetStore.DuplicateMpanException ex = assertThrows(
                InMemoryAssetStore.DuplicateMpanException.class,
                () -> store.register(request(MPAN, POSTCODE), fspId));

        assertThat(ex.getConflictingMpans()).containsExactly(MPAN);
    }

    @Test @DisplayName("register() handles a request with no network block")
    void register_noNetwork() {
        NewAssetRegistrationRequest req = request(MPAN, POSTCODE);
        req.setNetwork(null);

        AssetRegistrationResponse resp = store.register(req, fspId);

        assertThat(resp.getFmarAssetIdentifier()).isNotNull();
    }

    @Test @DisplayName("Distinct MPANs receive distinct identifiers")
    void register_distinctIdentifiers() {
        UUID first  = store.register(request(MPAN, POSTCODE), fspId).getFmarAssetIdentifier();
        UUID second = store.register(request("2000000000002", POSTCODE), fspId)
                .getFmarAssetIdentifier();

        assertThat(first).isNotEqualTo(second);
    }
}
