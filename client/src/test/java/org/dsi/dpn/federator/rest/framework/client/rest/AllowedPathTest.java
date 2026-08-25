// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.rest;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedPathTest {

    final AntPathMatcher matcher = new AntPathMatcher();

    // ── JSON array form (what DSM supplies) ───────────────────────────────────

    @Test @DisplayName("Parses a JSON array of request_type/request_path")
    void parse_jsonArray() {
        List<AllowedPath> parsed = AllowedPath.parse("""
                [{"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"},
                 {"request_type":"POST","request_path":"/api/v1/fmar/assets"}]
                """);

        assertThat(parsed).containsExactly(
                new AllowedPath("GET", "/api/v1/fmar/assets/{mpan}"),
                new AllowedPath("POST", "/api/v1/fmar/assets"));
    }

    @Test @DisplayName("Parses a JSON array without the enclosing brackets")
    void parse_jsonWithoutBrackets() {
        List<AllowedPath> parsed = AllowedPath.parse(
                "{\"request_type\":\"GET\",\"request_path\":\"/assets\"},"
                        + "{\"request_type\":\"POST\",\"request_path\":\"/fsp/{fspId}/assets\"}");

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(1).requestPath()).isEqualTo("/fsp/{fspId}/assets");
    }

    @Test @DisplayName("Accepts unquoted field names and single quotes")
    void parse_lenientJson() {
        List<AllowedPath> parsed = AllowedPath.parse(
                "[{request_type:'GET', request_path:'/assets'}]");

        assertThat(parsed).containsExactly(new AllowedPath("GET", "/assets"));
    }

    @Test @DisplayName("Recovers entries when fields are not comma-separated")
    void parse_looseForm() {
        List<AllowedPath> parsed = AllowedPath.parse(
                "{request_type:GET request_path:/api/v1/fmar/assets/{mpan}},"
                        + "{request_type:POST request_path:/api/v1/fmar/assets}");

        assertThat(parsed).containsExactly(
                new AllowedPath("GET", "/api/v1/fmar/assets/{mpan}"),
                new AllowedPath("POST", "/api/v1/fmar/assets"));
    }

    @Test @DisplayName("Skips entries missing request_type or request_path")
    void parse_skipsIncompleteEntries() {
        List<AllowedPath> parsed = AllowedPath.parse("""
                [{"request_type":"GET","request_path":"/assets"},
                 {"request_type":"POST"},
                 {"request_path":"/orphan"}]
                """);

        assertThat(parsed).containsExactly(new AllowedPath("GET", "/assets"));
    }

    // ── Legacy form (kept working) ─────────────────────────────────────────────

    @Test @DisplayName("Parses the legacy METHOD:path form")
    void parse_legacyMethodScoped() {
        List<AllowedPath> parsed = AllowedPath.parse(
                "GET:/api/v1/fmar/assets,POST:/api/v1/fmar/assets");

        assertThat(parsed).containsExactly(
                new AllowedPath("GET", "/api/v1/fmar/assets"),
                new AllowedPath("POST", "/api/v1/fmar/assets"));
    }

    @Test @DisplayName("Legacy bare paths permit any method")
    void parse_legacyBarePath() {
        List<AllowedPath> parsed = AllowedPath.parse("/api/v1/fmar/assets/**");

        assertThat(parsed).containsExactly(new AllowedPath("*", "/api/v1/fmar/assets/**"));
        assertThat(parsed.get(0).matches(matcher, "DELETE", "/api/v1/fmar/assets/x")).isTrue();
    }

    // ── Empty / unusable input ────────────────────────────────────────────────

    @Test @DisplayName("Blank or unparseable configuration yields no entries")
    void parse_emptyInputs() {
        assertThat(AllowedPath.parse(null)).isEmpty();
        assertThat(AllowedPath.parse("")).isEmpty();
        assertThat(AllowedPath.parse("   ")).isEmpty();
        assertThat(AllowedPath.parse("[]")).isEmpty();
        assertThat(AllowedPath.parse("{}")).isEmpty();
    }

    // ── Matching ──────────────────────────────────────────────────────────────

    @Test @DisplayName("matches() honours the method and the path template")
    void matches_methodAndPath() {
        AllowedPath entry = new AllowedPath("GET", "/api/v1/fmar/assets/{mpan}");

        assertThat(entry.matches(matcher, "GET", "/api/v1/fmar/assets/1000000000001")).isTrue();
        // Method must agree.
        assertThat(entry.matches(matcher, "POST", "/api/v1/fmar/assets/1000000000001")).isFalse();
        // Template variable covers one segment only.
        assertThat(entry.matches(matcher, "GET", "/api/v1/fmar/assets/1/2")).isFalse();
        // Different path.
        assertThat(entry.matches(matcher, "GET", "/api/v1/other/1")).isFalse();
    }

    @Test @DisplayName("matches() is case-insensitive on the method")
    void matches_caseInsensitiveMethod() {
        assertThat(new AllowedPath("get", "/assets").matches(matcher, "GET", "/assets")).isTrue();
        assertThat(new AllowedPath("GET", "/assets").matches(matcher, "get", "/assets")).isTrue();
    }

    @Test @DisplayName("matches() supports Ant wildcards")
    void matches_antWildcards() {
        AllowedPath entry = new AllowedPath("GET", "/api/v1/fmar/assets/**");

        assertThat(entry.matches(matcher, "GET", "/api/v1/fmar/assets/a/b/c")).isTrue();
    }

    @Test @DisplayName("A null method skips the method check")
    void matches_nullMethod() {
        // Used by the path-only convenience API — the method is simply not compared.
        assertThat(new AllowedPath("*", "/assets").matches(matcher, null, "/assets")).isTrue();
        assertThat(new AllowedPath("GET", "/assets").matches(matcher, null, "/assets")).isTrue();
        // The path must still match.
        assertThat(new AllowedPath("GET", "/assets").matches(matcher, null, "/other")).isFalse();
    }
}
