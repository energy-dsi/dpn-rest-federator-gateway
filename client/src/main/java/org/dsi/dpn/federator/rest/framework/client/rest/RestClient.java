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
import org.springframework.http.HttpStatusCode;
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
 * A call selects the target product by its (organisation, productName) — the two
 * labels a participant knows from getConsumerConfig — via {@link ProductKey}. The
 * internal producer id, base URL and mTLS client are resolved from consumer config.
 *
 * Two usage patterns supported:
 *
 *   SHORT-LIVED (call and exit):
 *     RestClient client = new RestClient();
 *     ProductKey key = new ProductKey("Elexon", "FMAR Asset Registration");
 *     String resp = client.get(key, "/api/v1/fmar/assets/1000000000001");
 *     // process exits — daemon watcher thread dies automatically
 *
 *   LONG-LIVED (held by a long-running process):
 *     RestClient client = new RestClient(); // created once at startup
 *     // ... time passes, cert rotates, watcher rebuilds WebClient ...
 *     String resp = client.post(key, "/api/v1/fmar/fsp/{fspId}/assets", payload);
 *     // cert rotation handled transparently
 *
 * Path authorisation:
 *   This client does NOT police request paths — it only does the plumbing
 *   (bootstrap/consumer config, mTLS, JWT, OCSP) and sends the call. The gateway
 *   ({@code DsiProductAuthorizationFilter}) is the authority: it enforces the
 *   product's allowed paths from producer config on every request, regardless of
 *   the client. An integrator that wants a client-side pre-check can read
 *   {@code getRegistration(key).allowedPaths()} (from ConsumerConfigDTO.topic) and
 *   validate against it themselves.
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

    /** ProductKey(organisation, productName) → current registration (rebuilt on cert rotation) */
    private final Map<ProductKey, ProductRegistration> registry = new ConcurrentHashMap<>();
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
    public RestClient(Map<ProductKey, ProductRegistration> initialRegistry,
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
     * @param key   the product's composite identity (organisation, productName, producerId)
     * @param path  API path e.g. /api/v1/fmar/assets?importMpan=...
     * @return response body as String
     * @throws IllegalArgumentException if no product is registered for {@code key}
     */
    public String get(ProductKey key, String path) {
        return get(key, path, null);
    }

    /**
     * Makes a GET call to the specified product and path, with additional request headers.
     *
     * @param key          the product's composite identity (organisation, productName, producerId)
     * @param path         API path (including any query string)
     * @param extraHeaders Additional headers to send (e.g. domain-specific sender headers).
     *                     May be null or empty. Authorization is always set by this client
     *                     and cannot be overridden here.
     * @return response body as String
     * @throws IllegalArgumentException if no product is registered for {@code key}
     */
    public String get(ProductKey key, String path, Map<String, String> extraHeaders) {
        ProductRegistration reg = resolve(key);
        return doBodyless("GET", reg.webClient().get(), reg, key, path, extraHeaders);
    }

    /**
     * Makes a DELETE call to the specified product and path.
     *
     * @param key   the product's composite identity (organisation, productName)
     * @param path  API path
     * @return response body as String
     * @throws IllegalArgumentException if no product is registered for {@code key}
     */
    public String delete(ProductKey key, String path) {
        return delete(key, path, null);
    }

    /** DELETE with additional request headers. See {@link #get(ProductKey, String, Map)}. */
    public String delete(ProductKey key, String path, Map<String, String> extraHeaders) {
        ProductRegistration reg = resolve(key);
        return doBodyless("DELETE", reg.webClient().delete(), reg, key, path, extraHeaders);
    }

    /** Shared implementation for the body-less verbs (GET, DELETE). */
    private String doBodyless(String method,
                              WebClient.RequestHeadersUriSpec<?> verbSpec,
                              ProductRegistration reg, ProductKey key, String path,
                              Map<String, String> extraHeaders) {
        String url = reg.baseUrl() + path;
        Span span = startSpan(method, key, reg, path);
        log.info("{} {}", method, url);
        try (Scope scope = span.makeCurrent()) {
            var spec = verbSpec
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + idpTokenService.fetchToken());
            applyExtraHeaders(spec, extraHeaders);
            Object resp = spec
                    .retrieve()
                    .onStatus((HttpStatusCode s) -> s == HttpStatus.UNAUTHORIZED, r -> {
                        log.warn("401 on {} {} — token may have expired", method, url);
                        return r.createException();
                    })
                    .bodyToMono(Object.class)
                    .timeout(timeout)
                    .block();
            String result = resp != null ? resp.toString() : null;
            log.info("{} {} → {}", method, url, result);
            return result;
        } catch (WebClientResponseException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException(method + " failed: HTTP " + e.getStatusCode()
                    + " url=" + url, e);
        } finally {
            span.end();
        }
    }

    /**
     * Makes a POST call to the specified product and path.
     *
     * @param key          the product's composite identity (organisation, productName)
     * @param path         API path e.g. /api/v1/fmar/fsp/{fspId}/assets
     * @param jsonPayload  Request body as JSON string
     * @return response body as String
     * @throws IllegalArgumentException if no product is registered for {@code key}
     */
    public String post(ProductKey key, String path, String jsonPayload) {
        return post(key, path, jsonPayload, null);
    }

    /**
     * Makes a POST call to the specified product and path, with additional request headers.
     *
     * @param key          the product's composite identity (organisation, productName)
     * @param path         API path (including any query string)
     * @param jsonPayload  Request body as JSON string
     * @param extraHeaders Additional headers to send (e.g. domain-specific sender headers).
     *                     May be null or empty. Authorization is always set by this client
     *                     and cannot be overridden here.
     * @return response body as String
     * @throws IllegalArgumentException if no product is registered for {@code key}
     */
    public String post(ProductKey key, String path, String jsonPayload,
                       Map<String, String> extraHeaders) {
        ProductRegistration reg = resolve(key);
        return doBody("POST", reg.webClient().post(), reg, key, path, jsonPayload, extraHeaders);
    }

    /** Makes a PUT call to the specified product and path. See {@link #post(ProductKey, String, String)}. */
    public String put(ProductKey key, String path, String jsonPayload) {
        return put(key, path, jsonPayload, null);
    }

    /** PUT with additional request headers. See {@link #post(ProductKey, String, String, Map)}. */
    public String put(ProductKey key, String path, String jsonPayload,
                      Map<String, String> extraHeaders) {
        ProductRegistration reg = resolve(key);
        return doBody("PUT", reg.webClient().put(), reg, key, path, jsonPayload, extraHeaders);
    }

    /** Makes a PATCH call to the specified product and path. See {@link #post(ProductKey, String, String)}. */
    public String patch(ProductKey key, String path, String jsonPayload) {
        return patch(key, path, jsonPayload, null);
    }

    /** PATCH with additional request headers. See {@link #post(ProductKey, String, String, Map)}. */
    public String patch(ProductKey key, String path, String jsonPayload,
                        Map<String, String> extraHeaders) {
        ProductRegistration reg = resolve(key);
        return doBody("PATCH", reg.webClient().patch(), reg, key, path, jsonPayload, extraHeaders);
    }

    /** Shared implementation for the body-carrying verbs (POST, PUT, PATCH). */
    private String doBody(String method,
                          WebClient.RequestBodyUriSpec verbSpec,
                          ProductRegistration reg, ProductKey key, String path,
                          String jsonPayload, Map<String, String> extraHeaders) {
        String url = reg.baseUrl() + path;
        Span span = startSpan(method, key, reg, path);
        log.info("{} {}", method, url);
        try (Scope scope = span.makeCurrent()) {
            var bodySpec = verbSpec
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + idpTokenService.fetchToken());
            applyExtraHeaders(bodySpec, extraHeaders);
            Object resp = bodySpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonPayload != null ? jsonPayload : "{}")
                    .retrieve()
                    .onStatus((HttpStatusCode s) -> s == HttpStatus.UNAUTHORIZED, r -> {
                        log.warn("401 on {} {} — token may have expired", method, url);
                        return r.createException();
                    })
                    .bodyToMono(Object.class)
                    .timeout(timeout)
                    .block();
            String result = resp != null ? resp.toString() : null;
            log.info("{} {} → {}", method, url, result);
            return result;
        } catch (WebClientResponseException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException(method + " failed: HTTP " + e.getStatusCode()
                    + " url=" + url, e);
        } finally {
            span.end();
        }
    }

    /** Builds the CLIENT span for an outbound call, tagged with the product identity. */
    private Span startSpan(String method, ProductKey key, ProductRegistration reg, String path) {
        return TRACER.spanBuilder("RestClient." + method.toLowerCase(java.util.Locale.ROOT))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", method)
                .setAttribute("url.path", path)
                .setAttribute("dpn.organisation", key.organisation())
                .setAttribute("dpn.product_name", key.productName())
                .setAttribute("dpn.producer_id", reg.producerId())
                .startSpan();
    }

    /**
     * Returns the registration for a data product by its composite key.
     * Exposes allowedPaths so caller can discover what paths are available.
     */
    public Optional<ProductRegistration> getRegistration(ProductKey key) {
        return Optional.ofNullable(registry.get(key));
    }

    /**
     * Returns all registered REST products, keyed by their composite identity.
     * Useful for callers that want to iterate over available producers/products.
     */
    public Map<ProductKey, ProductRegistration> getAllRegistrations() {
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

                ProductKey key = new ProductKey(producer.getName(), product.getName());

                ProductRegistration reg = new ProductRegistration(
                        producer.getName(),
                        producer.getIdpClientId(),
                        product.getName(),
                        baseUrl,
                        paths,
                        buildWebClient());

                registry.put(key, reg);
                log.info("REST product registered: {} baseUrl={} paths={}",
                        key, baseUrl, product.getTopic());
            }
        }
        logSubscriptions();
    }

    /**
     * Logs the full set of REST products this consumer is subscribed to — the
     * (organisation, productName) pairs a caller must use as a {@link ProductKey}.
     * Printed once after bootstrap so the available set is obvious in the logs.
     */
    private void logSubscriptions() {
        if (registry.isEmpty()) {
            log.warn("RestClient: no REST products subscribed — getConsumerConfig returned no "
                    + "products of type 'rest'. No calls will resolve until a subscription exists.");
            return;
        }
        log.info("RestClient subscriptions ({}) — call these via new ProductKey(organisation, productName):",
                registry.size());
        int i = 1;
        for (ProductKey k : registry.keySet()) {
            log.info("  [{}] organisation='{}', productName='{}'", i++, k.organisation(), k.productName());
        }
    }

    /** Human-readable list of the subscribed (organisation, productName) pairs, for error messages. */
    private String subscriptionsSummary() {
        if (registry.isEmpty()) {
            return "(none — this consumer has no REST subscriptions)";
        }
        StringBuilder sb = new StringBuilder();
        for (ProductKey k : registry.keySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("organisation='").append(k.organisation())
              .append("', productName='").append(k.productName()).append('\'');
        }
        return sb.toString();
    }

    // ── Certificate file watcher ──────────────────────────────────────────────

    protected void startCertWatcher() {
        String keystorePath = commonProps.getProperty(KEYSTORE_PATH);
        if (keystorePath == null || keystorePath.isBlank()) {
            log.warn("RestClient: idp.keystore.path not set — cert rotation watcher not started");
            return;
        }

        // When vault.tls.enabled=true the TLS material comes from Vault (rotated in
        // memory by VaultTlsSupport), so there is no keystore file/dir on disk to
        // watch. Skip quietly rather than failing the watcher thread with a
        // NoSuchFileException on a directory that will never exist.
        Path parentDir = Paths.get(keystorePath).getParent();
        if (parentDir == null || !java.nio.file.Files.isDirectory(parentDir)) {
            log.info("RestClient: keystore directory '{}' not present (TLS likely sourced from "
                    + "Vault) — file-based cert rotation watcher not started", parentDir);
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
                            registry.replaceAll((pk, reg) ->
                                    new ProductRegistration(
                                            reg.organisation(), reg.producerId(), reg.productName(),
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

    /**
     * Resolves the product registration for a call and performs the client-side OCSP check.
     *
     * <p>This client is responsible only for the plumbing — bootstrapping from consumer config,
     * mTLS, JWT and OCSP. It deliberately does <em>not</em> validate the request path against the
     * product's allowed paths: that is the gateway's job ({@code DsiProductAuthorizationFilter}
     * enforces it authoritatively from the verified JWT and DSM config). An integrator that wants
     * to pre-check a path can read {@code getRegistration(key).allowedPaths()} and do so itself.
     *
     * @throws IllegalArgumentException if no product is registered for {@code key}
     * @throws IllegalStateException    if the producer's certificate fails the OCSP check
     */
    protected ProductRegistration resolve(ProductKey key) {
        ProductRegistration reg = registry.get(key);
        if (reg == null) {
            log.warn("Product mismatch — requested organisation='{}', productName='{}', but this "
                            + "consumer is not subscribed to it. Subscribed products ({}): {}. "
                            + "Check the organisation/product name against the subscriptions list "
                            + "logged at startup (they must match getConsumerConfig exactly).",
                    key.organisation(), key.productName(), registry.size(), subscriptionsSummary());
            throw new IllegalArgumentException(
                    "No REST product registered for " + key
                            + ". Subscribed products: " + subscriptionsSummary()
                            + ". Has RestClient bootstrapped correctly, and are you subscribed "
                            + "to this organisation's product?");
        }

        // Client-side OCSP check — verify the producer certificate is not revoked
        // before making any outbound call. Uses producerId from registry since
        // OCSP checks the producer's Keycloak certificate.
        OcspStatus ocspStatus = ocspService.verify(reg.producerId());
        if (ocspStatus != OcspStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Outbound call blocked — producer certificate OCSP status="
                            + ocspStatus + " for " + key
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
     * @param organisation the producing organisation's name (ProducerDTO.name)
     * @param producerId   Keycloak client ID of the producer DPN (ProducerDTO.idpClientId)
     * @param productName  Human-readable product name from DSM (ProductDTO.name)
     * @param baseUrl      https://host:port
     * @param allowedPaths method+path entries parsed from ProductDTO.topic — what
     *                     this product exposes. Validated client-side before each
     *                     call, and enforced again server-side by
     *                     DsiProductAuthorizationFilter.
     * @param webClient    mTLS WebClient — rebuilt automatically on cert rotation
     *                     by the file watcher thread.
     */
    public record ProductRegistration(
            String            organisation,
            String            producerId,
            String            productName,
            String            baseUrl,
            List<AllowedPath> allowedPaths,
            WebClient         webClient) {

        /**
         * Optional client-side pre-check: does this product's allowed-path configuration permit
         * {@code method} on {@code path}? Lets an integrator fail fast, before a round-trip, instead
         * of receiving a 403 from the gateway. Purely advisory — the gateway enforces the same rule
         * authoritatively regardless. Any query string on {@code path} is ignored (paths are matched
         * on the path only), using the same {@link org.springframework.util.AntPathMatcher} semantics
         * (URI templates like {@code {id}} and Ant wildcards {@code *}/{@code **}) the gateway uses.
         *
         * @param method HTTP method, e.g. {@code "GET"}
         * @param path   request path, with or without a query string
         * @return {@code true} if some allowed-path entry matches
         */
        public boolean isAllowed(String method, String path) {
            if (path == null) {
                return false;
            }
            int q = path.indexOf('?');
            String pathOnly = q >= 0 ? path.substring(0, q) : path;
            org.springframework.util.AntPathMatcher matcher = new org.springframework.util.AntPathMatcher();
            return allowedPaths.stream().anyMatch(entry -> entry.matches(matcher, method, pathOnly));
        }
    }
}