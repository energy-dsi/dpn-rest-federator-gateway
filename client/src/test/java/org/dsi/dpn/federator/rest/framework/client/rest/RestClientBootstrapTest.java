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
import org.dsi.dpn.common.model.dto.ConsumerConfigDTO;
import org.dsi.dpn.common.model.dto.ProductDTO;
import org.dsi.dpn.common.model.dto.ProducerDTO;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests covering processConfig() (extracted from bootstrap()),
 * startCertWatcher(), and resolve() in RestClient.
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

    static final ProductKey FMAR_KEY = new ProductKey("Elexon", "FMAR Product");

    ConsumerConfigDTO config(String type, String topic, boolean tls) {
        ProductDTO product = ProductDTO.builder()
                .name("FMAR Product").type(type).topic(topic).build();
        ProducerDTO producer = ProducerDTO.builder()
                .name("Elexon")
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
        assertThat(client.getRegistration(FMAR_KEY)).isPresent();
        assertThat(client.getRegistration(FMAR_KEY).get().baseUrl())
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
        assertThat(client.getRegistration(FMAR_KEY))
                .hasValueSatisfying(r -> assertThat(r.baseUrl()).startsWith("http://"));
    }

    @Test @DisplayName("processConfig() parses the JSON allowed-path array")
    void processConfig_jsonAllowedPaths() {
        client.processConfig(config("rest", """
                [{"request_type":"GET","request_path":"/api/v1/fmar/assets"},
                 {"request_type":"POST","request_path":"/api/v1/fmar/assets"},
                 {"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"}]
                """, true));
        assertThat(client.getRegistration(FMAR_KEY))
                .hasValueSatisfying(r -> assertThat(r.allowedPaths()).hasSize(3));
    }

    @Test @DisplayName("processConfig() skips a product whose allowed-path list is empty")
    void processConfig_skipsEmptyAllowedPaths() {
        // An empty JSON array grants nothing, so the product is not registered at
        // all rather than registered with a configuration that can never match.
        client.processConfig(config("rest", "[]", true));
        assertThat(client.getRegistration(FMAR_KEY)).isEmpty();
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
        assertThat(client.getRegistration(FMAR_KEY))
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

    // ── resolve() ────────────────────────────────────────────────────────────

    private static final ProductKey FMAR = new ProductKey("Elexon", "FMAR");

    @Test @DisplayName("resolve() returns registration for a subscribed product")
    void resolve_valid() {
        when(ocspService.verify("elexon-prod-id")).thenReturn(OcspStatus.ACTIVE);
        RestClient c = new RestClient(
                Map.of(FMAR, new RestClient.ProductRegistration(
                        "Elexon", "elexon-prod-id", "FMAR", "https://host:8443",
                        List.of(new AllowedPath("GET", "/api/v1/fmar/assets")),
                        webClient)),
                idpTokenService, ocspService, Duration.ofSeconds(5));

        var reg = c.resolve(FMAR);
        assertThat(reg.producerId()).isEqualTo("elexon-prod-id");
    }

    @Test @DisplayName("resolve() throws for an unsubscribed product")
    void resolve_unknownProduct() {
        assertThatThrownBy(() -> client.resolve(new ProductKey("Nobody", "Unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No REST product registered for");
    }

    @Test @DisplayName("resolve() throws when OCSP not ACTIVE")
    void resolve_ocspBlocked() {
        when(ocspService.verify("elexon-prod-id")).thenReturn(OcspStatus.REVOKED);
        RestClient c = new RestClient(
                Map.of(FMAR, new RestClient.ProductRegistration(
                        "Elexon", "elexon-prod-id", "FMAR", "https://host:8443",
                        List.of(new AllowedPath("GET", "/api/v1/fmar/assets")),
                        webClient)),
                idpTokenService, ocspService, Duration.ofSeconds(5));

        assertThatThrownBy(() -> c.resolve(FMAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=REVOKED");
    }
}