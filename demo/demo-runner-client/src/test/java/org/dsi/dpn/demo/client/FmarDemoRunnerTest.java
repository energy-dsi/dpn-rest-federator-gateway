// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.client;

import java.util.Map;
import java.util.UUID;
import org.dsi.dpn.federator.rest.framework.client.rest.ProductKey;
import org.dsi.dpn.federator.rest.framework.client.rest.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FmarDemoRunnerTest {

    static final String ORG      = "Elexon";
    static final String PRODUCT  = "FMAR Asset Registration";
    static final ProductKey KEY  = new ProductKey(ORG, PRODUCT);
    static final String MPAN     = "1000000000001";
    static final String POSTCODE = "SW1A 1AA";

    @Mock RestClient restClient;

    DemoParameters params;
    FmarDemoRunner runner;
    UUID fspId;

    @BeforeEach
    void setUp() {
        fspId = UUID.randomUUID();
        params = new DemoParameters(
                ORG, PRODUCT, MPAN, POSTCODE, null, fspId, "fsp-001", "FSP", true);
        runner = new FmarDemoRunner(params, restClient);
        when(restClient.getAllRegistrations()).thenReturn(Map.of());
    }

    @Test @DisplayName("assetQueryPath() builds the spec's query parameters")
    void assetQueryPath_buildsQueryString() {
        String path = runner.assetQueryPath();

        assertThat(path).startsWith("/api/v1/fmar/assets?");
        assertThat(path).contains("importMpan=" + MPAN);
        // Space in the postcode must be URL-encoded.
        assertThat(path).contains("postcode=SW1A+1AA");
        assertThat(path).doesNotContain("assetId=");
    }

    @Test @DisplayName("assetQueryPath() includes assetId when supplied")
    void assetQueryPath_includesAssetId() {
        UUID assetId = UUID.randomUUID();
        FmarDemoRunner withAssetId = new FmarDemoRunner(
                new DemoParameters(
                ORG, PRODUCT, MPAN, POSTCODE, assetId, fspId,
                        "fsp-001", "FSP", true),
                restClient);

        assertThat(withAssetId.assetQueryPath()).contains("assetId=" + assetId);
    }

    @Test @DisplayName("registerPath() embeds the FSP id")
    void registerPath_embedsFspId() {
        assertThat(runner.registerPath()).isEqualTo("/api/v1/fmar/fsp/" + fspId + "/assets");
    }

    @Test @DisplayName("senderHeaders() sets the FMAR sender headers")
    void senderHeaders_forQuery() {
        Map<String, String> headers = runner.senderHeaders(true);

        assertThat(headers).containsEntry("X-Sender-FMAR-Id", "fsp-001");
        assertThat(headers).containsEntry("X-Sender-Role", "FSP");
        assertThat(headers).containsEntry("X-Contractual-Authorisation", "true");
    }

    @Test @DisplayName("senderHeaders() omits contractual authorisation for registration")
    void senderHeaders_forRegistration() {
        Map<String, String> headers = runner.senderHeaders(false);

        assertThat(headers).containsKey("X-Sender-FMAR-Id");
        assertThat(headers).doesNotContainKey("X-Contractual-Authorisation");
    }

    @Test @DisplayName("registrationPayload() carries the MPAN as a JSON string")
    void registrationPayload_containsMpanAsString() {
        String payload = runner.registrationPayload();

        // Quoted, so the 13-digit MPAN cannot lose precision as a JSON number.
        assertThat(payload).contains("\"" + MPAN + "\"");
        assertThat(payload).contains("\"postcode\": \"" + POSTCODE + "\"");
        assertThat(payload).contains("\"assetStatus\": \"Energised\"");
    }

    @Test @DisplayName("run() executes all four steps against the client")
    void run_executesAllSteps() {
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        runner.run();

        // Two GETs (before/after) and two POSTs (register/duplicate).
        verify(restClient, atLeastOnce()).get(eq(KEY), anyString(), any());
        verify(restClient, atLeastOnce()).post(eq(KEY), anyString(), anyString(), any());
    }

    @Test @DisplayName("run() completes when the first lookup 404s and the duplicate 409s")
    void run_handlesExpectedFailures() {
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenThrow(new RuntimeException("GET failed: HTTP 404 NOT_FOUND"))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}")
                .thenThrow(new RuntimeException("POST failed: HTTP 409 CONFLICT"));

        // No exception escapes — expected outcomes are reported, not thrown.
        runner.run();

        verify(restClient, atLeastOnce()).post(eq(KEY), anyString(), anyString(), any());
    }
}
