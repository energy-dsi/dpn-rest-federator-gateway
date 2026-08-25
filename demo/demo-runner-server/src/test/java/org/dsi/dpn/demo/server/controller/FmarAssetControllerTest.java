// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.server.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.dsi.dpn.demo.server.model.AssetRegistrationResponse;
import org.dsi.dpn.demo.server.model.AssetStatus;
import org.dsi.dpn.demo.server.model.Location;
import org.dsi.dpn.demo.server.model.Network;
import org.dsi.dpn.demo.server.model.NewAssetRegistrationRequest;
import org.dsi.dpn.demo.server.model.SenderRole;
import org.dsi.dpn.demo.server.store.InMemoryAssetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FmarAssetControllerTest {

    static final String MPAN      = "1000000000001";
    static final String POSTCODE  = "SW1A 1AA";
    static final String SENDER_ID = "fsp-001";

    @Mock InMemoryAssetStore assetStore;

    FmarAssetController controller;
    UUID fspId;

    @BeforeEach
    void setUp() {
        controller = new FmarAssetController(assetStore);
        fspId = UUID.randomUUID();
    }

    NewAssetRegistrationRequest request(AssetStatus status) {
        return NewAssetRegistrationRequest.builder()
                .assetName("Battery Unit 1")
                .installedCapacity(2.5)
                .generationStorageIndicator(true)
                .demandIndicator(false)
                .assetStatus(status)
                .network(Network.builder().importMpans(List.of(MPAN)).build())
                .location(Location.builder().postcode(POSTCODE).build())
                .build();
    }

    @Test @DisplayName("GET /assets returns 200 when the asset exists")
    void getAsset_found() {
        AssetRegistrationResponse stored = AssetRegistrationResponse.builder()
                .fmarAssetIdentifier(UUID.randomUUID())
                .assetName("Battery Unit 1")
                .build();
        when(assetStore.find(MPAN, POSTCODE, null)).thenReturn(Optional.of(stored));

        ResponseEntity<AssetRegistrationResponse> resp = controller.getAsset(
                MPAN, POSTCODE, null, SENDER_ID, SenderRole.FSP, true);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getAssetName()).isEqualTo("Battery Unit 1");
    }

    @Test @DisplayName("GET /assets returns 404 when no asset matches")
    void getAsset_notFound() {
        when(assetStore.find(MPAN, POSTCODE, null)).thenReturn(Optional.empty());

        ResponseEntity<AssetRegistrationResponse> resp = controller.getAsset(
                MPAN, POSTCODE, null, SENDER_ID, SenderRole.FSP, true);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("POST /fsp/{fspId}/assets returns 200 with the new identifier")
    void registerAsset_created() {
        UUID assigned = UUID.randomUUID();
        when(assetStore.register(any(), eq(fspId))).thenReturn(
                AssetRegistrationResponse.builder().fmarAssetIdentifier(assigned).build());

        ResponseEntity<AssetRegistrationResponse> resp = controller.registerAsset(
                fspId, SENDER_ID, SenderRole.FSP, request(AssetStatus.ENERGISED));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getFmarAssetIdentifier()).isEqualTo(assigned);
    }

    @Test @DisplayName("POST rejects an assetStatus not permitted on registration")
    void registerAsset_invalidStatus() {
        assertThatThrownBy(() -> controller.registerAsset(
                fspId, SENDER_ID, SenderRole.FSP, request(AssetStatus.WITHDRAWN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted on registration");
    }

    @Test @DisplayName("POST propagates DuplicateMpanException for the handler to map to 409")
    void registerAsset_duplicate() {
        when(assetStore.register(any(), eq(fspId)))
                .thenThrow(new InMemoryAssetStore.DuplicateMpanException(List.of(MPAN)));

        assertThatThrownBy(() -> controller.registerAsset(
                fspId, SENDER_ID, SenderRole.FSP, request(AssetStatus.ENERGISED)))
                .isInstanceOf(InMemoryAssetStore.DuplicateMpanException.class);
    }
}
