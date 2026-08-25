// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * Renders REST results as readable log blocks.
 *
 * <p>RestClient returns response bodies as a single-line string. Printed raw, an
 * FMAR asset is an unreadable wall of text in {@code kubectl logs}, so each result
 * is boxed with its request line and the JSON is pretty-printed.
 */
final class ResultFormatter {

    private static final int WIDTH = 78;
    private static final String BORDER = "═".repeat(WIDTH);
    private static final String THIN   = "─".repeat(WIDTH);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter prettyWriter = mapper.writerWithDefaultPrettyPrinter();

    private ResultFormatter() {
    }

    static ResultFormatter create() {
        return new ResultFormatter();
    }

    /** Section heading for a demo step. */
    String step(int number, String title) {
        return "\n" + BORDER
                + "\n  STEP " + number + " — " + title
                + "\n" + BORDER;
    }

    /** A successful result: request line, outcome, then the pretty-printed body. */
    String success(String method, String path, String body) {
        return "\n" + THIN
                + "\n  " + method + " " + path
                + "\n  Result: SUCCESS"
                + "\n" + THIN
                + "\n" + indent(prettyPrint(body))
                + "\n" + THIN;
    }

    /** An expected non-success outcome (e.g. a 404 before registration). */
    String expected(String method, String path, String outcome, String detail) {
        return "\n" + THIN
                + "\n  " + method + " " + path
                + "\n  Result: " + outcome + " (expected)"
                + (detail != null && !detail.isBlank() ? "\n  Detail: " + detail : "")
                + "\n" + THIN;
    }

    /** An unexpected failure. */
    String failure(String method, String path, String detail) {
        return "\n" + THIN
                + "\n  " + method + " " + path
                + "\n  Result: FAILED"
                + "\n  Detail: " + detail
                + "\n" + THIN;
    }

    String banner(String title) {
        return "\n" + BORDER + "\n  " + title + "\n" + BORDER;
    }

    /** Key/value summary block, used to echo the run's inputs. */
    String parameters(DemoParameters params) {
        return "\n" + THIN
                + "\n  Product name  : " + params.productName()
                + "\n  Import MPAN   : " + params.importMpan()
                + "\n  Postcode      : " + params.postcode()
                + "\n  Asset id      : " + (params.assetId() != null ? params.assetId() : "(none)")
                + "\n  FSP id        : " + params.fspId()
                + "\n  Sender id     : " + params.senderFmarId()
                + "\n  Sender role   : " + params.senderRole()
                + "\n  Contractual   : " + params.contractualAuthorisation()
                + "\n" + THIN;
    }

    /**
     * Pretty-prints a JSON string. Bodies that are not valid JSON (or are empty)
     * are returned as-is rather than failing the run — the point is to show the
     * result, whatever shape it arrived in.
     */
    String prettyPrint(String body) {
        if (body == null || body.isBlank()) return "(empty response body)";
        try {
            return prettyWriter.writeValueAsString(mapper.readTree(body));
        } catch (Exception e) {
            return body;
        }
    }

    private String indent(String text) {
        return text.lines().map(line -> "  " + line)
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
