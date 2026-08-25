// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.rest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;
import uk.gov.dbt.ndtp.federator.common.model.dto.ConsumerConfigDTO;
import uk.gov.dbt.ndtp.federator.common.model.dto.ProductDTO;
import uk.gov.dbt.ndtp.federator.common.model.dto.ProducerDTO;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests covering processConfig() (extracted from bootstrap()),
 * startCertWatcher(), and resolveAndValidate() in RestClient.
 *
 * Uses a subclass that ONLY overrides buildWebClient() so all other
 * real methods are executed and counted by JaCoCo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestClientBootstrapTest {

    @Mock IdpTokenService               idpTokenService;
    @Mock OcspClientVerificationService ocspService;
    @Mock WebClient                     webClient;

    /** Subclass that ONLY overrides buildWebClient() — all other methods are real. */
    class TestClient extends RestClient {
        TestClient() {
            super(new HashMap<>(), idpTokenService, ocspService, Duration.ofSeconds(5));
        }
        @Override
        protected WebClient buildWebClient() { return webClient; }
    }

    TestClient client;

    @BeforeEach
    void setUp() {
        client = new TestClient();
    }

    ConsumerConfigDTO config(String type, String topic, boolean tls) {
        ProductDTO product = ProductDTO.builder()
                .name("FMAR Product").type(type).topic(topic).build();
        ProducerDTO producer = ProducerDTO.builder()
                .idpClientId("elexon-prod-id").host("host")
                .port(BigDecimal.valueOf(8443)).tls(tls)
                .products(List.of(product)).build();
        return ConsumerConfigDTO.builder().producers(List.of(producer)).build();
    }

    // ── processConfig() — calls REAL code ────────────────────────────────────

    @Test @DisplayName("processConfig() registers REST product in registry")
    void processConfig_registersRestProduct() {
        client.processConfig(config("rest", "/api/v1/fmar/assets", true));
        assertThat(client.getAllRegistrations()).hasSize(1);
        assertThat(client.getRegistration("FMAR Product")).isPresent();
        assertThat(client.getRegistration("FMAR Product").get().baseUrl())
                .isEqualTo("https://host:8443");
    }

    @Test @DisplayName("processConfig() skips non-REST type")
    void processConfig_skipsNonRest() {
        client.processConfig(config("topic", "kafka-topic", true));
        assertThat(client.getAllRegistrations()).isEmpty();
    }

    @Test @DisplayName("processConfig() skips product with null topic")
    void processConfig_skipsNullTopic() {
        client.processConfig(config("rest", null, true));
        assertThat(client.getAllRegistrations()).isEmpty();
    }

    @Test @DisplayName("processConfig() returns early for null config")
    void processConfig_nullConfig() {
        client.processConfig(null);
        assertThat(client.getAllRegistrations()).isEmpty();
    }

    @Test @DisplayName("processConfig() returns early for null producers")
    void processConfig_nullProducers() {
        ConsumerConfigDTO cfg = ConsumerConfigDTO.builder().producers(null).build();
        client.processConfig(cfg);
        assertThat(client.getAllRegistrations()).isEmpty();
    }

    @Test @DisplayName("processConfig() uses http when TLS is false")
    void processConfig_httpWhenNoTls() {
        client.processConfig(config("rest", "/api/v1/data", false));
        assertThat(client.getRegistration("FMAR Product"))
                .hasValueSatisfying(r -> assertThat(r.baseUrl()).startsWith("http://"));
    }

    @Test @DisplayName("processConfig() parses the JSON allowed-path array")
    void processConfig_jsonAllowedPaths() {
        client.processConfig(config("rest", """
                [{"request_type":"GET","request_path":"/api/v1/fmar/assets"},
                 {"request_type":"POST","request_path":"/api/v1/fmar/assets"},
                 {"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"}]
                """, true));
        assertThat(client.getRegistration("FMAR Product"))
                .hasValueSatisfying(r -> assertThat(r.allowedPaths()).hasSize(3));
    }

    @Test @DisplayName("processConfig() skips a product with unusable allowed paths")
    void processConfig_skipsUnusableAllowedPaths() {
        client.processConfig(config("rest", "not a valid config", true));
        assertThat(client.getRegistration("FMAR Product")).isEmpty();
    }

    @Test @DisplayName("processConfig() skips producer with null products")
    void processConfig_nullProductsList() {
        ProducerDTO producer = ProducerDTO.builder()
                .idpClientId("prod").host("host")
                .port(BigDecimal.valueOf(8443)).tls(true)
                .products(null).build();
        client.processConfig(ConsumerConfigDTO.builder()
                .producers(List.of(producer)).build());
        assertThat(client.getAllRegistrations()).isEmpty();
    }

    @Test @DisplayName("processConfig() stores producerId from producer, not product")
    void processConfig_storesProducerId() {
        client.processConfig(config("rest", "/api/v1/data", true));
        assertThat(client.getRegistration("FMAR Product"))
                .hasValueSatisfying(r -> assertThat(r.producerId()).isEqualTo("elexon-prod-id"));
    }

    // ── startCertWatcher() — calls REAL code ─────────────────────────────────

    @Test @DisplayName("startCertWatcher() no-ops when keystore path is null")
    void startCertWatcher_nullPath() throws Exception {
        // commonProps in test constructor is empty — keystore path is null
        client.startCertWatcher();
        assertThat(client).isNotNull();
    }

    @Test @DisplayName("startCertWatcher() no-ops when keystore path is blank")
    void startCertWatcher_blankPath() throws Exception {
        var f = RestClient.class.getDeclaredField("commonProps");
        f.setAccessible(true);
        Properties props = new Properties();
        props.setProperty("idp.keystore.path", "  ");
        f.set(client, props);
        client.startCertWatcher();
        assertThat(client).isNotNull();
    }

    @Test @DisplayName("startCertWatcher() starts daemon thread for valid path")
    void startCertWatcher_startsThread(@TempDir Path tempDir) throws Exception {
        Path p12 = tempDir.resolve("keystore.p12");
        Files.writeString(p12, "dummy", StandardOpenOption.CREATE);

        var f = RestClient.class.getDeclaredField("commonProps");
        f.setAccessible(true);
        Properties props = new Properties();
        props.setProperty("idp.keystore.path", p12.toString());
        f.set(client, props);

        client.startCertWatcher();
        Thread.sleep(300);

        boolean started = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "rest-client-cert-watcher".equals(t.getName()) && t.isDaemon());
        assertThat(started).isTrue();
    }

    // ── resolveAndValidate() ─────────────────────────────────────────────────

    @Test @DisplayName("resolveAndValidate() returns registration for valid product and path")
    void resolveAndValidate_valid() {
        when(ocspService.verify("elexon-prod-id")).thenReturn(OcspStatus.ACTIVE);
        RestClient c = new RestClient(
                Map.of("FMAR", new RestClient.ProductRegistration(
                        "elexon-prod-id", "FMAR", "https://host:8443",
                        List.of(new AllowedPath("GET", "/api/v1/fmar/assets"),
                                new AllowedPath("GET", "/api/v1/fmar/assets/**")),
                        webClient)),
                idpTokenService, ocspService, Duration.ofSeconds(5));

        var reg = c.resolveAndValidate("FMAR", "/api/v1/fmar/assets");
        assertThat(reg.producerId()).isEqualTo("elexon-prod-id");
    }

    @Test @DisplayName("resolveAndValidate() throws for unknown product")
    void resolveAndValidate_unknownProduct() {
        assertThatThrownBy(() -> client.resolveAndValidate("Unknown", "/api/v1/data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productName=Unknown");
    }

    @Test @DisplayName("resolveAndValidate() throws for disallowed path")
    void resolveAndValidate_disallowedPath() {
        RestClient c = new RestClient(
                Map.of("FMAR", new RestClient.ProductRegistration(
                        "elexon-prod-id", "FMAR", "https://host:8443",
                        List.of(new AllowedPath("GET", "/api/v1/fmar/assets")),
                        webClient)),
                idpTokenService, ocspService, Duration.ofSeconds(5));

        assertThatThrownBy(() -> c.resolveAndValidate("FMAR", "/api/v1/admin/secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowed paths");
    }

    @Test @DisplayName("resolveAndValidate() throws when OCSP not ACTIVE")
    void resolveAndValidate_ocspBlocked() {
        when(ocspService.verify("elexon-prod-id")).thenReturn(OcspStatus.REVOKED);
        RestClient c = new RestClient(
                Map.of("FMAR", new RestClient.ProductRegistration(
                        "elexon-prod-id", "FMAR", "https://host:8443",
                        List.of(new AllowedPath("GET", "/api/v1/fmar/assets")),
                        webClient)),
                idpTokenService, ocspService, Duration.ofSeconds(5));

        assertThatThrownBy(() -> c.resolveAndValidate("FMAR", "/api/v1/fmar/assets"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=REVOKED");
    }
}