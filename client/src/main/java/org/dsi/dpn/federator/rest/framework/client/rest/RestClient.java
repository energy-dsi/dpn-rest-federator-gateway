// SPDX-License-Identifier: Apache-2.0

package org.dsi.dpn.federator.rest.framework.client.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import org.dsi.dpn.common.management.ManagementNodeDataHandler;
import org.dsi.dpn.common.service.config.ConsumerConfigService;
import org.dsi.dpn.common.storage.InMemoryConfigurationStore;
import org.dsi.dpn.common.model.dto.ConsumerConfigDTO;
import org.dsi.dpn.common.model.dto.ProducerDTO;
import org.dsi.dpn.common.model.dto.ProductDTO;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.utils.IdpTokenServiceFactory;
import org.dsi.dpn.common.utils.HttpClientFactoryUtils;
import org.dsi.dpn.common.utils.ObjectMapperUtil;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.SSLUtils;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationServiceImpl;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspStatus;

import org.dsi.dpn.common.storage.InMemoryConfigurationStore;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;

/**
 * FRAMEWORK — do not modify.
 *
 * Self-contained REST client for the DSI REST Federator consumer side.
 * No dependency on FederatorClient, ClientDynamicConfigJob, or Kafka.
 *
 * At construction:
 *   1. Initialises PropertyUtil from common.configuration (same file as gRPC Federator)
 *   2. Uses ConsumerConfigService.getConsumerConfiguration() to fetch ConsumerConfigDTO
 *   3. Filters for type="rest" products only
 *   4. Builds ProductRegistration per producer:
 *        baseUrl = https://host:port
 *        allowedPaths = method+path entries parsed from ProductDTO.topic
 *        webClient = mTLS WebClient built from P12 keystore
 *   5. Starts a file watcher on the P12 keystore file — rebuilds WebClient
 *      automatically when the cert manager rotates the certificate.
 *      No restart required. No job cycle dependency.
 *
 * Two usage patterns supported:
 *
 *   SHORT-LIVED (call and exit):
 *     RestClient client = new RestClient();
 *     String resp = client.get("elexon-prod-id", "/api/v1/fmar/assets/1000000000001");
 *     // process exits — daemon watcher thread dies automatically
 *
 *   LONG-LIVED (held by Kafka consumer or other long-running process):
 *     RestClient client = new RestClient(); // created once at startup
 *     // ... time passes, cert rotates, watcher rebuilds WebClient ...
 *     String resp = client.post("elexon-prod-id", "/api/v1/fmar/assets", payload);
 *     // cert rotation handled transparently
 *
 * Path validation:
 *   Before making any HTTP call, validates the request's method and path against
 *   the allowedPaths for that producer (from ConsumerConfigDTO.topic, a JSON array
 *   of {request_type, request_path}). Throws IllegalArgumentException if the
 *   request is not permitted, giving a clear client-side error rather than a
 *   cryptic 403 from the server. Uses AntPathMatcher — same as
 *   DsiProductAuthorizationFilter on the server.
 *
 * Configuration (all from existing common.configuration — no new properties):
 *   idp.keystore.path + password    — PKCS12 for mTLS + JWT signing
 *   idp.truststore.path + password  — JKS for verifying producer cert
 *   idp.auth.mode                   — private_key_jwt (recommended)
 *   management.node.base.url        — Management Node endpoint
 *
 * Optional (from client.properties, defaulted):
 *   federator.rest.read.timeout.seconds — HTTP read timeout (default 60)
 */
@Slf4j
public class RestClient {

    private static final String COMMON_CONFIG    = "common.configuration";
    private static final String TIMEOUT_PROP     = "federator.rest.read.timeout.seconds";
    private static final String TIMEOUT_DEFAULT  = "60";
    private static final String KEYSTORE_PATH    = "idp.keystore.path";
    private static final String KEYSTORE_PASS    = "idp.keystore.password";
    private static final String TRUSTSTORE_PATH  = "idp.truststore.path";
    private static final String TRUSTSTORE_PASS  = "idp.truststore.password";
    private static final String PRODUCT_TYPE_REST = "rest";
    private static final String BEARER           = "Bearer ";

    private static final Tracer TRACER =
            OpenTelemetryConfig.get().getTracer("org.dsi.dpn.federator.rest.framework.client.rest");

    private final IdpTokenService idpTokenService;
    private final Duration        timeout;
    private final Properties      commonProps;

    /** productName → current registration (rebuilt on cert rotation) */
    private final Map<String, ProductRegistration> registry = new ConcurrentHashMap<>();
    private final OcspClientVerificationService ocspService;

    /**
     * Constructs a self-bootstrapping RestClient.
     *
     * Calls Management Node to discover REST data products,
     * builds mTLS WebClient from P12, and starts certificate file watcher.
     *
     * Requires PropertyUtil to be already initialised
     * (PropertyUtil.init("client.properties") or equivalent).
     */
    public RestClient() {
        this.commonProps      = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
        this.idpTokenService  = IdpTokenServiceFactory.createIdpTokenService();
        this.ocspService      = new OcspClientVerificationServiceImpl(this.commonProps, this.idpTokenService);
        this.timeout          = Duration.ofSeconds(
                Long.parseLong(PropertyUtil.getPropertyValue(TIMEOUT_PROP, TIMEOUT_DEFAULT)));

        // Bootstrap: fetch consumer config and populate registry
        bootstrap();

        // Watch P12 file — rebuild WebClient when cert manager rotates cert
        startCertWatcher();

        log.info("RestClient initialised: {} REST product(s) registered", registry.size());
    }


    /**
     * Testing constructor — accepts pre-populated registry and pre-built
     * dependencies. No PropertyUtil, no Management Node, no SSL required.
     * Public so tests in any package can use it via subclassing.
     */
    public RestClient(Map<String, ProductRegistration> initialRegistry,
                      IdpTokenService idpTokenService,
                      OcspClientVerificationService ocspService,
                      Duration timeout) {
        this.commonProps     = new Properties();
        this.idpTokenService = idpTokenService;
        this.ocspService     = ocspService;
        this.timeout         = timeout;
        this.registry.putAll(initialRegistry);
        // No bootstrap, no cert watcher in test constructor
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Makes a GET call to the specified product and path.
     *
     * @param productName  Name of the data product as registered in DSM
     * @param path         API path e.g. /api/v1/fmar/assets/{mpan}
     * @return response body as String
     * @throws IllegalArgumentException if product not found or path not in allowed paths
     */
    public String get(String productName, String path) {
        return get(productName, path, null);
    }

    /**
     * Makes a GET call to the specified product and path, with additional request headers.
     *
     * @param productName  Name of the data product as registered in DSM
     * @param path         API path (including any query string)
     * @param extraHeaders Additional headers to send (e.g. domain-specific sender headers).
     *                     May be null or empty. Authorization is always set by this client
     *                     and cannot be overridden here.
     * @return response body as String
     * @throws IllegalArgumentException if product not found or path not in allowed paths
     */
    public String get(String productName, String path, Map<String, String> extraHeaders) {
        return doGet(resolveAndValidate(productName, path, "GET"), productName, path, extraHeaders);
    }

    /**
     * Makes a GET call WITHOUT validating the path against the registered product's
     * allowed paths — bypasses the same local pre-check {@link #resolveAndValidate}
     * performs. Deliberately for demonstrating/testing server-side enforcement (a
     * call this method lets through still has to pass {@code DsiProductAuthorizationFilter}
     * on the gateway) — never use this for real traffic; use {@link #get(String, String)}.
     */
    public String getUnchecked(String productName, String path, Map<String, String> extraHeaders) {
        ProductRegistration reg = registry.get(productName);
        if (reg == null) {
            throw new IllegalArgumentException(
                    "No REST product registered for productName=" + productName
                            + ". Available products: " + registry.keySet());
        }
        return doGet(reg, productName, path, extraHeaders);
    }

    private String doGet(ProductRegistration reg, String productName, String path,
                         Map<String, String> extraHeaders) {
        String url = reg.baseUrl() + path;
        Span span = TRACER.spanBuilder("RestClient.get")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", "GET")
                .setAttribute("url.path", path)
                .setAttribute("dpn.product_name", productName)
                .setAttribute("dpn.producer_id", reg.producerId())
                .startSpan();
        log.info("GET {}", url);
        try (Scope scope = span.makeCurrent()) {
            var spec = reg.webClient().get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + idpTokenService.fetchToken());
            applyExtraHeaders(spec, extraHeaders);
            Object resp = spec
                    .retrieve()
                    .onStatus(s -> s == HttpStatus.UNAUTHORIZED, r -> {
                        log.warn("401 on GET {} — token may have expired", url);
                        return r.createException();
                    })
                    .bodyToMono(Object.class)
                    .timeout(timeout)
                    .block();
            String result = resp != null ? resp.toString() : null;
            log.info("GET {} → {}", url, result);
            return result;
        } catch (WebClientResponseException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException("GET failed: HTTP " + e.getStatusCode()
                    + " url=" + url, e);
        } finally {
            span.end();
        }
    }

    /**
     * Makes a POST call to the specified product and path.
     *
     * @param productName  Name of the data product as registered in DSM
     * @param path         API path e.g. /api/v1/fmar/assets
     * @param jsonPayload  Request body as JSON string
     * @return response body as String
     * @throws IllegalArgumentException if product not found or path not in allowed paths
     */
    public String post(String productName, String path, String jsonPayload) {
        return post(productName, path, jsonPayload, null);
    }

    /**
     * Makes a POST call to the specified product and path, with additional request headers.
     *
     * @param productName  Name of the data product as registered in DSM
     * @param path         API path (including any query string)
     * @param jsonPayload  Request body as JSON string
     * @param extraHeaders Additional headers to send (e.g. domain-specific sender headers).
     *                     May be null or empty. Authorization is always set by this client
     *                     and cannot be overridden here.
     * @return response body as String
     * @throws IllegalArgumentException if product not found or path not in allowed paths
     */
    public String post(String productName, String path, String jsonPayload,
                       Map<String, String> extraHeaders) {
        ProductRegistration reg = resolveAndValidate(productName, path, "POST");
        String url = reg.baseUrl() + path;
        Span span = TRACER.spanBuilder("RestClient.post")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", "POST")
                .setAttribute("url.path", path)
                .setAttribute("dpn.product_name", productName)
                .setAttribute("dpn.producer_id", reg.producerId())
                .startSpan();
        log.info("POST {}", url);
        try (Scope scope = span.makeCurrent()) {
            var bodySpec = reg.webClient().post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + idpTokenService.fetchToken());
            applyExtraHeaders(bodySpec, extraHeaders);
            Object resp = bodySpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonPayload != null ? jsonPayload : "{}")
                    .retrieve()
                    .onStatus(s -> s == HttpStatus.UNAUTHORIZED, r -> {
                        log.warn("401 on POST {} — token may have expired", url);
                        return r.createException();
                    })
                    .bodyToMono(Object.class)
                    .timeout(timeout)
                    .block();
            String result = resp != null ? resp.toString() : null;
            log.info("POST {} → {}", url, result);
            return result;
        } catch (WebClientResponseException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException("POST failed: HTTP " + e.getStatusCode()
                    + " url=" + url, e);
        } finally {
            span.end();
        }
    }

    /**
     * Returns the registration for a data product by product name.
     * Exposes allowedPaths so caller can discover what paths are available.
     */
    public Optional<ProductRegistration> getRegistration(String productName) {
        return Optional.ofNullable(registry.get(productName));
    }

    /**
     * Returns all registered REST products.
     * Useful for callers that want to iterate over available producers.
     */
    public Map<String, ProductRegistration> getAllRegistrations() {
        return Map.copyOf(registry);
    }

    // ── Bootstrap — call Management Node at construction ─────────────────────

    protected void bootstrap() {
        log.info("RestClient bootstrapping — fetching consumer config from Management Node");
        try {
            ManagementNodeDataHandler handler = new ManagementNodeDataHandler(
                    () -> HttpClientFactoryUtils.createHttpClientWithMtls(commonProps),
                    ObjectMapperUtil.getInstance(),
                    idpTokenService);
            ConsumerConfigService configService = new ConsumerConfigService(
                    handler, InMemoryConfigurationStore.getInstance());
            processConfig(configService.getConsumerConfiguration());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "RestClient bootstrap failed — could not fetch consumer config: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Protected — processes ConsumerConfigDTO to populate the registry.
     * Extracted from bootstrap() to allow unit testing without Management Node.
     */
    protected void processConfig(ConsumerConfigDTO config) {
        if (config == null || config.getProducers() == null) {
            log.warn("RestClient: no consumer config returned from Management Node");
            return;
        }
        for (ProducerDTO producer : config.getProducers()) {
            if (producer.getProducts() == null) continue;
            for (ProductDTO product : producer.getProducts()) {
                if (!PRODUCT_TYPE_REST.equalsIgnoreCase(product.getType())) continue;
                if (product.getTopic() == null) continue;

                String scheme  = Boolean.TRUE.equals(producer.getTls()) ? "https" : "http";
                String hostName = producer.getHost().split("://").length > 1 ? producer.getHost().split("://")[1] : producer.getHost();
                String baseUrl = scheme + "://" + hostName
//                String baseUrl = producer.getHost()
                        + ":" + producer.getPort();
                List<AllowedPath> paths = AllowedPath.parse(product.getTopic());
                if (paths.isEmpty()) {
                    log.warn("Skipping REST product '{}' — no usable allowed-path "
                            + "configuration in its topic field", product.getName());
                    continue;
                }

                ProductRegistration reg = new ProductRegistration(
                        producer.getIdpClientId(),
                        product.getName(),
                        baseUrl,
                        paths,
                        buildWebClient());

                registry.put(product.getName(), reg);
                log.info("REST product registered: productName={} producerId={} baseUrl={} paths={}",
                        product.getName(), producer.getIdpClientId(),
                        baseUrl, product.getTopic());
            }
        }
    }

    // ── Certificate file watcher ──────────────────────────────────────────────

    protected void startCertWatcher() {
        String keystorePath = commonProps.getProperty(KEYSTORE_PATH);
        if (keystorePath == null || keystorePath.isBlank()) {
            log.warn("RestClient: idp.keystore.path not set — cert rotation watcher not started");
            return;
        }

        Thread watchThread = new Thread(() -> {
            try {
                Path p12Path = Paths.get(keystorePath);
                Path dir     = p12Path.getParent();
                String file  = p12Path.getFileName().toString();

                WatchService watcher = FileSystems.getDefault().newWatchService();
                dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                log.info("RestClient cert watcher started [watching={}]", keystorePath);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.take(); // blocks until file change
                    for (var event : key.pollEvents()) {
                        if (file.equals(event.context().toString())) {
                            log.info("P12 file changed — rebuilding WebClient with new certificate");
                            WebClient newWebClient = buildWebClient();
                            // Replace WebClient in all registrations atomically
                            registry.replaceAll((producerId, reg) ->
                                    new ProductRegistration(
                                            reg.producerId(), reg.productName(),
                                            reg.baseUrl(), reg.allowedPaths(), newWebClient));
                            log.info("WebClient rebuilt for {} producer(s)", registry.size());
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("RestClient cert watcher stopped");
            } catch (Exception e) {
                log.error("RestClient cert watcher error: {}", e.getMessage(), e);
            }
        }, "rest-client-cert-watcher");

        watchThread.setDaemon(true); // dies when main process exits
        watchThread.start();
    }

    // ── Header handling ───────────────────────────────────────────────────────

    /**
     * Applies caller-supplied headers to a request spec. Authorization is owned by
     * this client (set before this call) and is never overridden by extraHeaders.
     */
    private void applyExtraHeaders(WebClient.RequestHeadersSpec<?> spec,
                                   Map<String, String> extraHeaders) {
        if (extraHeaders == null || extraHeaders.isEmpty()) return;
        extraHeaders.forEach((name, value) -> {
            if (name == null || value == null) return;
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                log.warn("Ignoring caller-supplied Authorization header — managed by RestClient");
                return;
            }
            spec.header(name, value);
        });
    }

    // ── Path validation ───────────────────────────────────────────────────────

    protected ProductRegistration resolveAndValidate(String productName, String path) {
        return resolveAndValidate(productName, path, null);
    }

    /**
     * Resolves the product registration and validates the request is permitted.
     *
     * <p>Mirrors the server-side check in DsiProductAuthorizationFilter so a call
     * that would be rejected there fails fast here instead of on the wire.
     *
     * <p>Any query string on {@code path} is stripped before matching, since path
     * patterns describe paths only.
     */
    protected ProductRegistration resolveAndValidate(String productName, String path,
                                                     String method) {
        ProductRegistration reg = registry.get(productName);
        if (reg == null) {
            throw new IllegalArgumentException(
                    "No REST product registered for productName=" + productName
                            + ". Available products: " + registry.keySet()
                            + ". Has RestClient bootstrapped correctly?");
        }

        // Ant patterns match paths only — drop any query string before comparing.
        int queryIdx = path.indexOf('?');
        String pathOnly = queryIdx >= 0 ? path.substring(0, queryIdx) : path;

        // Validate method+path against allowedPaths (same rules as the server filter)
        org.springframework.util.AntPathMatcher matcher =
                new org.springframework.util.AntPathMatcher();
        boolean allowed = reg.allowedPaths().stream()
                .anyMatch(entry -> entry.matches(matcher, method, pathOnly));

        if (!allowed) {
            throw new IllegalArgumentException(
                    "Request '" + (method != null ? method + " " : "") + pathOnly
                            + "' is not in the allowed paths for productName="
                            + productName + ". Allowed: " + reg.allowedPaths()
                            + ". Check the topic field in the DSM product configuration.");
        }

        // Client-side OCSP check — verify producer certificate is not revoked
        // before making any outbound call. Uses producerId from registry (not
        // productName) since OCSP checks the producer's Keycloak certificate.
        OcspStatus ocspStatus = ocspService.verify(reg.producerId());
        if (ocspStatus != OcspStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Outbound call blocked — producer certificate OCSP status="
                            + ocspStatus + " for productName=" + productName
                            + " producerId=" + reg.producerId()
                            + ". Call cancelled to prevent connection to a compromised DPN.");
        }

        return reg;
    }

    // ── WebClient builder — reads fresh mTLS material on every call ──────────

    protected WebClient buildWebClient() {
        try {
            // Same switch HttpClientFactoryUtils.createHttpClientWithMtls() uses:
            // when vault.tls.enabled=true the cert manager's Vault-stored material
            // is used directly, in memory — there is no keystore file on disk for
            // this codepath, so falling through to the file-based branch below
            // would fail with "file not found" rather than a clear error.
            javax.net.ssl.SSLContext jdkContext;
            if (org.dsi.dpn.common.service.secret.VaultTlsSupport.isVaultTlsEnabled()) {
                jdkContext = org.dsi.dpn.common.service.secret.VaultTlsSupport.sslContext();
            } else {
                String keystorePath   = commonProps.getProperty(KEYSTORE_PATH);
                String keystorePass   = commonProps.getProperty(KEYSTORE_PASS);
                String truststorePath = commonProps.getProperty(TRUSTSTORE_PATH);
                String truststorePass = commonProps.getProperty(TRUSTSTORE_PASS);

                // SSLUtils.createSSLContext() returns javax.net.ssl.SSLContext —
                // same call used by HttpClientFactoryUtils.createHttpClientWithMtls().
                jdkContext = SSLUtils.createSSLContext(
                        keystorePath, keystorePass, truststorePath, truststorePass);
            }

            // Wrap it in JdkSslContext which implements io.netty.handler.ssl.SslContext
            // — the type that SslContextSpec.sslContext(SslContext) accepts.
            io.netty.handler.ssl.SslContext nettySslContext =
                    new io.netty.handler.ssl.JdkSslContext(
                            jdkContext,
                            true,                                           // isClient
                            io.netty.handler.ssl.ClientAuth.OPTIONAL);     // clientAuth

            HttpClient httpClient = HttpClient.create()
                    .secure(ssl -> ssl.sslContext(nettySslContext));

            return WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build mTLS WebClient: " + e.getMessage(), e);
        }
    }


    /**
     * Registration for a single REST data product.
     *
     * @param producerId   Keycloak client ID of the producer DPN
     * @param productName  Human-readable product name from DSM
     * @param baseUrl      https://host:port
     * @param allowedPaths method+path entries parsed from ProductDTO.topic — what
     *                     this product exposes. Validated client-side before each
     *                     call, and enforced again server-side by
     *                     DsiProductAuthorizationFilter.
     * @param webClient    mTLS WebClient — rebuilt automatically on cert rotation
     *                     by the file watcher thread.
     */
    public record ProductRegistration(
            String            producerId,
            String            productName,
            String            baseUrl,
            List<AllowedPath> allowedPaths,
            WebClient         webClient) {}
}