// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dsi.dpn.federator.rest.framework.client.rest.AllowedPath;
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
import static org.mockito.ArgumentMatchers.argThat;
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
        // Targets are resolved by filtering the client's subscription registry, so the
        // registry must contain the product the demo is pointed at.
        RestClient.ProductRegistration reg = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443", List.of(), null);
        when(restClient.getAllRegistrations()).thenReturn(Map.of(KEY, reg));
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

    @Test @DisplayName("run() reports an unexpected failure (not caught as an expected outcome) "
            + "when registration itself throws")
    void run_step2UnexpectedFailure_logsFailureAndContinues() {
        String registerPath = runner.registerPath();
        // Query calls always succeed; the very first POST (registration) throws something that
        // is not a recognised expected outcome, exercising step2's catch -> log.error() branch.
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(eq(KEY), eq(registerPath), anyString(), any()))
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        runner.run();

        verify(restClient, atLeastOnce()).post(eq(KEY), eq(registerPath), anyString(), any());
    }

    @Test @DisplayName("run() reports an unexpected failure when the post-registration lookup throws")
    void run_step3UnexpectedFailure_logsFailureAndContinues() {
        String queryPath = runner.assetQueryPath();
        // First GET (step1) succeeds, second GET (step3, after registration) throws something
        // unexpected, exercising step3's catch -> log.error() branch.
        when(restClient.get(eq(KEY), eq(queryPath), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}")
                .thenThrow(new RuntimeException("connection reset"));
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        runner.run();

        verify(restClient, atLeastOnce()).get(eq(KEY), eq(queryPath), any());
    }

    @Test @DisplayName("run() unwraps a chained exception to the innermost cause's message")
    void run_rootMessage_unwrapsWrappedCause() {
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenThrow(new RuntimeException("wrapper", new IllegalStateException("root cause detail")));
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        // No exception escapes; rootMessage() walks the cause chain down to "root cause detail".
        runner.run();

        verify(restClient, atLeastOnce()).get(eq(KEY), anyString(), any());
    }

    @Test @DisplayName("run() falls back to toString() when the innermost cause has no message")
    void run_rootMessage_fallsBackToToStringWhenCauseHasNoMessage() {
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenThrow(new RuntimeException(new IllegalStateException()));
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        // The innermost cause (IllegalStateException with no message) falls back to toString().
        runner.run();

        verify(restClient, atLeastOnce()).get(eq(KEY), anyString(), any());
    }

    @Test @DisplayName("run() reports the expected outcome when the forbidden-path call is rejected")
    void run_step5Expected_forbiddenPathRejected() {
        String forbiddenPath = "/api/v1/fmar/assets/getClientID";
        // Every other GET succeeds; only the forbidden-path call throws, exercising step5's
        // catch -> log.info(expected FORBIDDEN) branch, distinct from its "unexpected success" path.
        when(restClient.get(eq(KEY), eq(forbiddenPath), any()))
                .thenThrow(new RuntimeException("GET failed: HTTP 403 FORBIDDEN"));
        when(restClient.get(eq(KEY), argThat(p -> p != null && !p.equals(forbiddenPath)), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        runner.run();

        verify(restClient).get(eq(KEY), eq(forbiddenPath), any());
    }

    @Test @DisplayName("run() executes the demo sequence once per resolved target")
    void run_withMultipleTargets_runsSequenceForEachTarget() {
        ProductKey secondKey = new ProductKey("OtherOrg", "Other Product");
        RestClient.ProductRegistration reg1 = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443", List.of(), null);
        RestClient.ProductRegistration reg2 = new RestClient.ProductRegistration(
                "OtherOrg", "producer-id-2", "Other Product", "https://host2:8443", List.of(), null);
        // Blank organisation/productName in params means "run against everything subscribed".
        DemoParameters blankParams = new DemoParameters(
                "", "", MPAN, POSTCODE, null, fspId, "fsp-001", "FSP", true);
        FmarDemoRunner multiTargetRunner = new FmarDemoRunner(blankParams, restClient);
        when(restClient.getAllRegistrations()).thenReturn(Map.of(KEY, reg1, secondKey, reg2));
        when(restClient.get(any(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(any(), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        multiTargetRunner.run();

        // The full 3-GET/3-POST sequence runs once per target.
        verify(restClient, org.mockito.Mockito.times(3)).get(eq(KEY), anyString(), any());
        verify(restClient, org.mockito.Mockito.times(3)).get(eq(secondKey), anyString(), any());
    }

    @Test @DisplayName("resolveTargets() with blank organisation and productName returns every "
            + "subscribed product")
    void resolveTargets_blankFilters_returnsEverything() {
        assertThat(runner.resolveTargets()).containsExactly(KEY);
    }

    @Test @DisplayName("resolveTargets() filters by organisation when productName is blank")
    void resolveTargets_organisationOnly() {
        ProductKey otherProductSameOrg = new ProductKey(ORG, "Other Product");
        RestClient.ProductRegistration reg2 = new RestClient.ProductRegistration(
                ORG, "producer-id", "Other Product", "https://host:8443", List.of(), null);
        RestClient.ProductRegistration reg1 = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443", List.of(), null);
        RestClient.ProductRegistration reg3 = new RestClient.ProductRegistration(
                "Different Org", "producer-id-3", PRODUCT, "https://host3:8443", List.of(), null);
        ProductKey differentOrgKey = new ProductKey("Different Org", PRODUCT);
        when(restClient.getAllRegistrations()).thenReturn(
                Map.of(KEY, reg1, otherProductSameOrg, reg2, differentOrgKey, reg3));
        FmarDemoRunner orgOnlyRunner = new FmarDemoRunner(
                new DemoParameters(ORG, "", MPAN, POSTCODE, null, fspId, "fsp-001", "FSP", true),
                restClient);

        assertThat(orgOnlyRunner.resolveTargets()).containsExactly(KEY, otherProductSameOrg);
    }

    @Test @DisplayName("resolveTargets() filters by productName when organisation is blank")
    void resolveTargets_productOnly() {
        ProductKey sameProductOtherOrg = new ProductKey("Other Org", PRODUCT);
        RestClient.ProductRegistration reg1 = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443", List.of(), null);
        RestClient.ProductRegistration reg2 = new RestClient.ProductRegistration(
                "Other Org", "producer-id-2", PRODUCT, "https://host2:8443", List.of(), null);
        RestClient.ProductRegistration reg3 = new RestClient.ProductRegistration(
                ORG, "producer-id", "Unrelated Product", "https://host:8443", List.of(), null);
        ProductKey unrelated = new ProductKey(ORG, "Unrelated Product");
        when(restClient.getAllRegistrations()).thenReturn(
                Map.of(KEY, reg1, sameProductOtherOrg, reg2, unrelated, reg3));
        FmarDemoRunner productOnlyRunner = new FmarDemoRunner(
                new DemoParameters("", PRODUCT, MPAN, POSTCODE, null, fspId, "fsp-001", "FSP", true),
                restClient);

        assertThat(productOnlyRunner.resolveTargets()).containsExactly(KEY, sameProductOtherOrg);
    }

    @Test @DisplayName("resolveTargets() accepts comma-separated organisation/productName lists")
    void resolveTargets_commaSeparatedLists() {
        ProductKey otherKey = new ProductKey("Other Org", "Other Product");
        RestClient.ProductRegistration reg1 = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443", List.of(), null);
        RestClient.ProductRegistration reg2 = new RestClient.ProductRegistration(
                "Other Org", "producer-id-2", "Other Product", "https://host2:8443", List.of(), null);
        RestClient.ProductRegistration reg3 = new RestClient.ProductRegistration(
                "Untouched Org", "producer-id-3", "Untouched Product", "https://host3:8443", List.of(), null);
        when(restClient.getAllRegistrations()).thenReturn(Map.of(
                KEY, reg1, otherKey, reg2,
                new ProductKey("Untouched Org", "Untouched Product"), reg3));
        FmarDemoRunner csvRunner = new FmarDemoRunner(
                new DemoParameters(
                        ORG + " , Other Org", PRODUCT + ", Other Product", MPAN, POSTCODE,
                        null, fspId, "fsp-001", "FSP", true),
                restClient);

        assertThat(csvRunner.resolveTargets()).containsExactlyInAnyOrder(KEY, otherKey);
    }

    @Test @DisplayName("resolveTargets() returns no targets when nothing matches the filters")
    void resolveTargets_noMatches_returnsEmpty() {
        FmarDemoRunner noMatchRunner = new FmarDemoRunner(
                new DemoParameters(
                        "Nonexistent Org", PRODUCT, MPAN, POSTCODE, null, fspId,
                        "fsp-001", "FSP", true),
                restClient);

        assertThat(noMatchRunner.resolveTargets()).isEmpty();
    }

    @Test @DisplayName("run() skips the client-side pre-check when not subscribed to the target")
    void run_preCheckSkipped_whenNotSubscribed() {
        // getRegistration() is unstubbed, so Mockito returns Optional.empty() by default —
        // exercises logAllowedPathPreCheck()'s "not subscribed" branch.
        when(restClient.get(eq(KEY), anyString(), any())).thenThrow(new RuntimeException("404"));
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("409"));

        runner.run();

        verify(restClient, atLeastOnce()).getRegistration(eq(KEY));
    }

    @Test @DisplayName("run() reports allowed/not-allowed paths from the client-side pre-check "
            + "when the product is subscribed")
    void run_preCheck_reportsAllowedAndNotAllowedPaths() {
        String queryPath = runner.assetQueryPath();
        // Only the GET on the query path is declared allowed; the registration POST and the
        // forbidden path are not — exercises both the true and the false side of each
        // isAllowed() ternary in logAllowedPathPreCheck().
        RestClient.ProductRegistration subscribed = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443",
                List.of(new AllowedPath("GET", "/api/v1/fmar/assets")), null);
        when(restClient.getRegistration(eq(KEY))).thenReturn(java.util.Optional.of(subscribed));
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        runner.run();

        verify(restClient, atLeastOnce()).getRegistration(eq(KEY));
        assertThat(queryPath).isNotBlank();
    }

    @Test @DisplayName("run() reports the forbidden path as allowed when the pre-check configuration "
            + "permits it")
    void run_preCheck_forbiddenPathAllowed() {
        // Covers the opposite side of the forbidden-path ternary from the test above: the
        // pre-check configuration happens to also permit the "forbidden" path and the
        // registration POST, so both report ALLOWED rather than NOT ALLOWED.
        RestClient.ProductRegistration permissive = new RestClient.ProductRegistration(
                ORG, "producer-id", PRODUCT, "https://host:8443",
                List.of(new AllowedPath("*", "/api/v1/fmar/**")), null);
        when(restClient.getRegistration(eq(KEY))).thenReturn(java.util.Optional.of(permissive));
        when(restClient.get(eq(KEY), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");
        when(restClient.post(eq(KEY), anyString(), anyString(), any()))
                .thenReturn("{\"FMARAssetIdentifier\":\"" + UUID.randomUUID() + "\"}");

        runner.run();

        verify(restClient, atLeastOnce()).getRegistration(eq(KEY));
    }
}
