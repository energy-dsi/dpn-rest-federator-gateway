// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.client;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultFormatterTest {

    ResultFormatter fmt;

    @BeforeEach
    void setUp() {
        fmt = ResultFormatter.create();
    }

    @Test @DisplayName("prettyPrint() indents valid JSON across multiple lines")
    void prettyPrint_validJson() {
        String out = fmt.prettyPrint("{\"assetName\":\"Battery\",\"installedCapacity\":2.5}");

        assertThat(out).contains("\"assetName\"");
        assertThat(out.lines().count()).isGreaterThan(1);
    }

    @Test @DisplayName("prettyPrint() returns non-JSON bodies unchanged")
    void prettyPrint_nonJsonPassthrough() {
        assertThat(fmt.prettyPrint("{assetName=Battery}")).isEqualTo("{assetName=Battery}");
    }

    @Test @DisplayName("prettyPrint() labels an empty body rather than printing nothing")
    void prettyPrint_emptyBody() {
        assertThat(fmt.prettyPrint(null)).isEqualTo("(empty response body)");
        assertThat(fmt.prettyPrint("  ")).isEqualTo("(empty response body)");
    }

    @Test @DisplayName("success() includes the request line and the body")
    void success_includesRequestAndBody() {
        String out = fmt.success("GET", "/assets?importMpan=1000000000001",
                "{\"assetName\":\"Battery\"}");

        assertThat(out).contains("GET /assets?importMpan=1000000000001");
        assertThat(out).contains("SUCCESS");
        assertThat(out).contains("assetName");
    }

    @Test @DisplayName("expected() marks the outcome as expected")
    void expected_marksOutcome() {
        String out = fmt.expected("GET", "/assets", "NOT FOUND", "HTTP 404");

        assertThat(out).contains("NOT FOUND (expected)");
        assertThat(out).contains("HTTP 404");
    }

    @Test @DisplayName("failure() reports the detail")
    void failure_reportsDetail() {
        String out = fmt.failure("POST", "/fsp/x/assets", "connection refused");

        assertThat(out).contains("FAILED");
        assertThat(out).contains("connection refused");
    }

    @Test @DisplayName("parameters() lists every run input")
    void parameters_listsInputs() {
        DemoParameters params = new DemoParameters(
                "Org A", "Product A", "1000000000001", "SW1A 1AA", null,
                UUID.randomUUID(), "fsp-001", "FSP", true);

        String out = fmt.parameters(params);

        assertThat(out).contains("Product A");
        assertThat(out).contains("1000000000001");
        assertThat(out).contains("SW1A 1AA");
        assertThat(out).contains("(none)");   // no assetId supplied
        assertThat(out).contains("FSP");
    }

    @Test @DisplayName("step() and banner() render headings")
    void headings() {
        assertThat(fmt.step(2, "Register the asset")).contains("STEP 2 - Register the asset");
        assertThat(fmt.banner("DEMO COMPLETE")).contains("DEMO COMPLETE");
    }
}
