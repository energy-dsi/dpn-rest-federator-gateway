// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.client;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dsi.dpn.federator.rest.framework.client.rest.ProductKey;
import org.dsi.dpn.federator.rest.framework.client.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsi.dpn.common.telemetry.HeartbeatService;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * EXAMPLE CONSUMER — calls a producer's FMAR asset endpoints through the
 * REST Federator gateway.
 *
 * <p>This is not gateway code: it is an application that imports the
 * rest-federator-client library, exactly as a participant's own integration
 * would. Everything security-related (mTLS, JWT bearer tokens, OCSP revocation
 * checks, allowed-path validation, certificate hot-reload) is handled inside
 * {@link RestClient}; this class only builds requests and prints results.
 *
 * <p>Demo sequence (paths follow the MHHS FMAR specification, prefixed with
 * {@code /api/v1/fmar} for consistency with this gateway's other routes):
 * <ol>
 *   <li>{@code GET  /api/v1/fmar/assets?importMpan=…&postcode=…}  — expected to be absent (404)</li>
 *   <li>{@code POST /api/v1/fmar/fsp/{fspId}/assets}              — register the asset</li>
 *   <li>{@code GET  /api/v1/fmar/assets?importMpan=…&postcode=…}  — now present</li>
 *   <li>{@code POST /api/v1/fmar/fsp/{fspId}/assets}              — same MPAN again (409)</li>
 * </ol>
 *
 * <p>Runs as a one-shot process: parameters come from environment variables (set
 * by the Kubernetes Job), and the process exits when the sequence completes.
 */
public class FmarDemoRunner {

    private static final Logger log = LoggerFactory.getLogger(FmarDemoRunner.class);

    private static final Tracer TRACER =
            OpenTelemetryConfig.get().getTracer("org.dsi.dpn.demo.client");

    /** Component name for heartbeat / OTEL identity on the consumer side. */
    private static final String COMPONENT_NAME = "rest-federator-client";

    private static final String ASSETS_PATH = "/api/v1/fmar/assets";
    private static final String FSP_PATH_PREFIX = "/api/v1/fmar/fsp/";

    private final DemoParameters params;
    private final RestClient restClient;
    private final ResultFormatter fmt = ResultFormatter.create();

    public FmarDemoRunner(DemoParameters params) {
        this.params = params;
        // RestClient bootstraps from the Management Node at construction and
        // validates the product exists before any call is made.
        this.restClient = createRestClient();
    }

    /** Visible for testing — lets a pre-built RestClient be injected. */
    FmarDemoRunner(DemoParameters params, RestClient restClient) {
        this.params = params;
        this.restClient = restClient;
    }

    protected RestClient createRestClient() {
        return new RestClient();
    }

    public void run() {
        // Root span for the whole run so every log line below shares one trace_id.
        Span root = TRACER.spanBuilder("FmarDemoRunner.run")
                .setAttribute("dpn.organisation", params.organisation())
                .setAttribute("dpn.product_name", params.productName())
                .startSpan();
        try (Scope scope = root.makeCurrent()) {
            log.info(fmt.banner("DSI REST Federator — FMAR Demo Client"));
            log.info(fmt.parameters(params));

            restClient.getAllRegistrations().forEach((name, reg) ->
                    log.info("Available product: {} producerId={} baseUrl={}",
                            name, reg.producerId(), reg.baseUrl()));

            List<ProductKey> targets = resolveTargets();
            if (targets.isEmpty()) {
                log.warn("No target products resolved for organisation(s)='{}' productName(s)='{}'. "
                                + "Nothing to run — check the subscriptions list above.",
                        params.organisation(), params.productName());
            } else {
                log.info("Running the FMAR demo sequence against {} target(s): {}", targets.size(), targets);
            }

            int i = 1;
            for (ProductKey key : targets) {
                int idx = i++;
                // One child span per target so each organisation/product is distinct in the trace.
                inSpan("target[" + idx + "]." + key.organisation(), () -> runFor(key));
            }

            log.info(fmt.banner("DEMO COMPLETE"));
        } finally {
            root.end();
        }
    }

    /**
     * Resolves the (organisation, productName) targets to run against, from the ORGANISATION_NAME
     * and PRODUCT_NAME inputs (each of which may be a comma-separated list):
     * <ul>
     *   <li>organisation(s) given + one product — run that product against each organisation;</li>
     *   <li>organisation(s) given + a matching list of products — zipped pairwise;</li>
     *   <li>organisation blank — run against every subscribed product (optionally filtered to the
     *       named product name(s)), discovered from the client's consumer-config registry.</li>
     * </ul>
     */
    List<ProductKey> resolveTargets() {
        List<String> orgs = splitCsv(params.organisation());
        List<String> products = splitCsv(params.productName());
        List<ProductKey> targets = new ArrayList<>();
        if (orgs.isEmpty()) {
            // No organisation named → every subscribed product, optionally filtered by product name.
            for (ProductKey k : restClient.getAllRegistrations().keySet()) {
                if (products.isEmpty() || products.contains(k.productName())) {
                    targets.add(k);
                }
            }
        } else {
            for (int i = 0; i < orgs.size(); i++) {
                String product = products.isEmpty() ? ""
                        : products.size() == 1 ? products.get(0)
                        : products.get(Math.min(i, products.size() - 1));
                if (!product.isBlank()) {
                    targets.add(new ProductKey(orgs.get(i), product));
                }
            }
        }
        return targets;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    /** Runs the full FMAR demo sequence against a single (organisation, productName) target. */
    private void runFor(ProductKey key) {
        log.info(fmt.banner("Target: organisation='" + key.organisation()
                + "', productName='" + key.productName() + "'"));
        String queryPath = assetQueryPath();
        String registerPath = registerPath();

        // Optional client-side fail-fast: a participant can check a call against the product's
        // allowed paths (from consumer config) before making it. Purely advisory — the gateway
        // enforces the same rule authoritatively, which step 5 relies on.
        logAllowedPathPreCheck(key, queryPath, registerPath);

        inSpan("step1.lookupBeforeRegistration", () -> step1LookupBeforeRegistration(key, queryPath));
        inSpan("step2.register", () -> step2Register(key, registerPath));
        inSpan("step3.lookupAfterRegistration", () -> step3LookupAfterRegistration(key, queryPath));
        inSpan("step4.registerDuplicate", () -> step4RegisterDuplicate(key, registerPath));
        inSpan("step5.forbiddenPath", () -> step5ForbiddenPath(key));
    }

    /** Runs a demo step inside its own child span so each step is distinct in the trace. */
    private void inSpan(String name, Runnable step) {
        Span span = TRACER.spanBuilder(name).startSpan();
        try (Scope scope = span.makeCurrent()) {
            step.run();
        } finally {
            span.end();
        }
    }

    /**
     * Demonstrates the optional client-side allowed-path pre-check. Reads the product's
     * allowed paths from the registry the client bootstrapped from consumer config and
     * reports, for each path this run uses, whether it is permitted — the same check a
     * participant would run to fail fast instead of receiving a 403 from the gateway.
     */
    private void logAllowedPathPreCheck(ProductKey key, String queryPath, String registerPath) {
        var registration = restClient.getRegistration(key);
        if (registration.isEmpty()) {
            log.warn("Client-side pre-check skipped — not subscribed to {}", key);
            return;
        }
        var reg = registration.get();
        String forbiddenPath = ASSETS_PATH + "/getClientID";
        log.info("Client-side allowed-path pre-check (advisory; gateway enforces regardless):");
        log.info("  allowed paths from consumer config: {}", reg.allowedPaths());
        log.info("  GET  {} -> {}", ASSETS_PATH,
                reg.isAllowed("GET", queryPath) ? "ALLOWED" : "NOT ALLOWED");
        log.info("  POST {} -> {}", registerPath,
                reg.isAllowed("POST", registerPath) ? "ALLOWED" : "NOT ALLOWED");
        log.info("  GET  {} -> {}", forbiddenPath,
                reg.isAllowed("GET", forbiddenPath)
                        ? "ALLOWED" : "NOT ALLOWED (a participant could stop here; step 5 calls anyway to show the gateway also rejects it)");
    }

    // ── Steps ─────────────────────────────────────────────────────────────────

    private void step1LookupBeforeRegistration(ProductKey key, String path) {
        log.info(fmt.step(1, "Query asset before registration (expect not found)"));
        try {
            String body = restClient.get(key, path, senderHeaders(true));
            log.info(fmt.success("GET", path, body));
        } catch (Exception e) {
            log.info(fmt.expected("GET", path, "NOT FOUND", rootMessage(e)));
        }
    }

    private void step2Register(ProductKey key, String path) {
        log.info(fmt.step(2, "Register the asset"));
        try {
            String body = restClient.post(
                    key, path, registrationPayload(), senderHeaders(false));
            log.info(fmt.success("POST", path, body));
        } catch (Exception e) {
            log.error(fmt.failure("POST", path, rootMessage(e)));
        }
    }

    private void step3LookupAfterRegistration(ProductKey key, String path) {
        log.info(fmt.step(3, "Query asset after registration (expect found)"));
        try {
            String body = restClient.get(key, path, senderHeaders(true));
            log.info(fmt.success("GET", path, body));
        } catch (Exception e) {
            log.error(fmt.failure("GET", path, rootMessage(e)));
        }
    }

    private void step4RegisterDuplicate(ProductKey key, String path) {
        log.info(fmt.step(4, "Register the same MPAN again (expect conflict)"));
        try {
            String body = restClient.post(
                    key, path, registrationPayload(), senderHeaders(false));
            log.warn(fmt.failure("POST", path,
                    "Expected a 409 conflict but the request succeeded: " + body));
        } catch (Exception e) {
            log.info(fmt.expected("POST", path, "CONFLICT", rootMessage(e)));
        }
    }

    /**
     * Deliberately calls a path that is NOT in the DSM product's allowed-path
     * configuration. The rest-federator-client does not itself police paths — it
     * just makes the call — so the request reaches the gateway, where
     * {@code DsiProductAuthorizationFilter}'s Stage 3 (method+path authorisation)
     * rejects it. Both this client's log AND the gateway's log show the failure,
     * demonstrating that server-side enforcement is what actually protects the API.
     */
    private void step5ForbiddenPath(ProductKey key) {
        String path = ASSETS_PATH + "/getClientID";
        log.info(fmt.step(5, "Call a path not in the allowed-path configuration (expect rejection)"));
        try {
            String body = restClient.get(key, path, senderHeaders(true));
            log.warn(fmt.failure("GET", path,
                    "Expected the gateway to reject this path but the request succeeded: " + body));
        } catch (Exception e) {
            log.info(fmt.expected("GET", path, "FORBIDDEN — method+path not allowed", rootMessage(e)));
        }
    }

    // ── Request building ──────────────────────────────────────────────────────

    /** {@code GET /assets} with the spec's required query parameters. */
    String assetQueryPath() {
        StringBuilder path = new StringBuilder(ASSETS_PATH)
                .append("?importMpan=").append(encode(params.importMpan()))
                .append("&postcode=").append(encode(params.postcode()));
        if (params.assetId() != null) {
            path.append("&assetId=").append(encode(params.assetId().toString()));
        }
        return path.toString();
    }

    /** {@code POST /api/v1/fmar/fsp/{fspId}/assets}. */
    String registerPath() {
        return FSP_PATH_PREFIX + params.fspId() + "/assets";
    }

    /**
     * Headers required by the FMAR specification.
     *
     * @param includeContractualAuthorisation the query operation requires
     *        X-Contractual-Authorisation; the registration operation does not.
     */
    Map<String, String> senderHeaders(boolean includeContractualAuthorisation) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Sender-FMAR-Id", params.senderFmarId());
        headers.put("X-Sender-Role", params.senderRole());
        if (includeContractualAuthorisation) {
            headers.put("X-Contractual-Authorisation",
                    String.valueOf(params.contractualAuthorisation()));
        }
        return headers;
    }

    /**
     * FMAR001 registration body. Built as a string rather than via a shared model
     * class so this example stays independent of the backend's own types — a real
     * consumer would use whatever representation its systems already hold.
     */
    String registrationPayload() {
        return """
               {
                 "assetName": "Demo Battery Unit",
                 "installedCapacity": 2.5,
                 "generationStorageIndicator": true,
                 "demandIndicator": false,
                 "assetStatus": "Energised",
                 "energySourceTypes": ["Battery"],
                 "network": {
                   "importMpans": ["%s"],
                   "gspGroupId": "_A",
                   "connectionVoltage": "LV"
                 },
                 "location": {
                   "domesticPremisesIndicator": false,
                   "postcode": "%s"
                 },
                 "metering": {
                   "meteringArrangementType": "Asset Metered",
                   "meteringGranularity": "Half Hourly"
                 },
                 "registration": {
                   "contractualAuthorisationIndicator": %s
                 }
               }
               """.formatted(
                       params.importMpan(),
                       params.postcode(),
                       params.contractualAuthorisation());
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Innermost cause message — the useful part when RestClient wraps an HTTP error. */
    private String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Standalone entry point. Requires client.properties (via the
     * FEDERATOR_CLIENT_PROPERTIES environment variable or on the classpath) —
     * the same file the gRPC Federator client uses.
     */
    public static void main(String[] args) {
        org.dsi.dpn.common.telemetry.OpenTelemetryConfig.initialize();

        // Consumer-side heartbeat. Unlike the long-running gateway, which beats on a
        // 15-minute schedule, the client is a one-shot Job: HeartbeatService emits
        // immediately on start, so a run produces heartbeat.started, a single
        // component.heartbeat, and heartbeat.stopped bracketing the work — a beat
        // only while it is actually running, which is what the consumer side needs.
        HeartbeatService heartbeat = HeartbeatService.create(COMPONENT_NAME);
        heartbeat.start();
        try {
            initProperties();

            DemoParameters params;
            try {
                params = DemoParameters.resolve();
            } catch (IllegalArgumentException e) {
                // DemoParameters.resolve() throws this for more than one reason (missing
                // PRODUCT_NAME, or an ASSET_ID/FSP_ID that isn't a valid UUID) -- its own
                // message already names the actual problem, so surface only that.
                log.error(e.getMessage());
                System.exit(1);
                return;
            }

            try {
                new FmarDemoRunner(params).run();
            } catch (Exception e) {
                log.error("Demo run failed", e);
                System.exit(1);
            }
        } finally {
            // Explicit stop so heartbeat.stopped (with the total count) is emitted and
            // flushed on the normal path, rather than relying on a shutdown hook.
            heartbeat.stop();
        }
    }

    /**
     * PropertyUtil.init(String) is a classpath-only lookup, so a filesystem path
     * in FEDERATOR_CLIENT_PROPERTIES must be loaded via init(File) instead.
     */
    private static void initProperties() {
        String propsPath = System.getenv()
                .getOrDefault("FEDERATOR_CLIENT_PROPERTIES", "client.properties");
        File propsFile = new File(propsPath);
        if (propsFile.exists()) {
            PropertyUtil.init(propsFile);
        } else {
            PropertyUtil.init(propsPath);
        }
    }
}
