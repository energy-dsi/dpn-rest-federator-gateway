// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Map;
import java.util.function.Supplier;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.utils.IdpTokenServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches an {@code Authorization: Bearer <token>} header to the OTLP HTTP exporters, so the
 * OTel Collector can verify who is sending it telemetry, not just trust the TLS transport (see
 * {@link OtlpTruststoreSupport} for the separate, TLS-trust half of this connection).
 *
 * <p>Authenticates as the SAME {@code dpn-service-client} Keycloak client already used to gate
 * Redis access, via the SAME {@link IdpTokenService} instance -
 * {@link IdpTokenServiceFactory#createRedisIdpTokenService()} - rather than provisioning a
 * second Keycloak client or a second cached token. This is a deliberate simplification: since
 * both uses already point at the identical client_id/client_secret (see
 * {@code KeycloakSessionGuard}, common-configuration.properties' {@code redis.idp.*} block), a
 * separate {@code otel.idp.*} client would only add a second Keycloak round-trip and a second
 * Redis cache entry for no actual isolation benefit - unlike the Management Node's {@code idp.*}
 * client, which genuinely is a different client with a different auth mode (private_key_jwt/
 * mTLS) and does warrant its own token lifecycle. If OTLP export ever needs to authenticate as a
 * genuinely separate client from Redis-gating, that's the point to introduce a dedicated
 * {@code otel.idp.*} prefix and a corresponding factory method - not before.
 *
 * <p>The token itself is fetched, cached (Redis, TTL = the token's own {@code expires_in}) and
 * retried by the existing {@link IdpTokenService} machinery
 * ({@code IdpTokenServiceClientSecretImpl} + Resilience4j) - this class writes no new caching or
 * resilience logic of its own, and a cache hit here is shared with (not duplicated from) whatever
 * {@code KeycloakSessionGuard} already cached for Redis-gating.
 *
 * <p>Swaps the header supplier into the already-autoconfigured OTLP HTTP exporters using
 * {@code AutoConfiguredOpenTelemetrySdkBuilder}'s exporter-customizer hooks - the same mechanism
 * {@link OtlpTruststoreSupport} already uses for TLS trust. Both customizers are applied to the
 * same exporter instance independently in {@link OpenTelemetryConfig#initialize()} and don't
 * interact - one calls {@code .setSslContext(...)}, the other {@code .setHeaders(...)}, on
 * (possibly) the same {@code toBuilder()} chain.
 *
 * <p>Enabled via {@code OTEL_EXPORTER_OTLP_OAUTH_ENABLED=true}. When unset/false, {@link
 * #resolve()} returns {@code null} and OTLP export remains exactly as it is today - authenticated
 * only by TLS server trust, no Bearer token. Neither a missing/blank
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT} nor a Keycloak/token-fetch failure can fail application
 * startup: {@link OpenTelemetryConfig#initialize()} wraps the whole SDK build in a try/catch that
 * falls back to {@code OpenTelemetry.noop()}.
 *
 * <p><b>Why the {@link IdpTokenService} is built lazily, not in {@link #resolve()}:</b>
 * {@link OpenTelemetryConfig#initialize()} is called as the very first line of this
 * application's {@code main()}, deliberately before anything else - including before
 * {@code VaultFileBasedSslBundleInitializer.initialise()} has had a chance to run
 * {@code PropertyUtil.initializeProperties()}. {@code IdpTokenServiceFactory
 * .createRedisIdpTokenService()} requires {@code PropertyUtil} to already be initialised (to
 * read {@code server.properties}/{@code common-configuration.properties}), so calling it eagerly
 * from {@link #resolve()} throws {@code PropertyUtilException: Property Util not properly
 * initialised} on every single startup with this feature enabled - silently swallowed by
 * {@link OpenTelemetryConfig#initialize()}'s catch-all, which just falls back to
 * {@code OpenTelemetry.noop()} with no indication OAuth was ever attempted. Deferring
 * construction to the first real header lookup - which only happens on the first actual export
 * attempt, asynchronously, well after {@code main()} has finished running - sidesteps this
 * ordering problem entirely without having to reorder {@code main()} itself.
 */
final class OtelBearerTokenSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelBearerTokenSupport.class);

    static final String OAUTH_ENABLED_ENV = "OTEL_EXPORTER_OTLP_OAUTH_ENABLED";
    private static final String AUTHORIZATION = "Authorization";

    private final Supplier<IdpTokenService> idpTokenServiceSupplier;
    private volatile IdpTokenService idpTokenService;

    /**
     * Takes an already-resolved {@link IdpTokenService} directly - used by tests, and safe to
     * call eagerly since no {@code PropertyUtil}/factory call is involved.
     */
    OtelBearerTokenSupport(IdpTokenService idpTokenService) {
        this.idpTokenServiceSupplier = null;
        this.idpTokenService = idpTokenService;
    }

    private OtelBearerTokenSupport(Supplier<IdpTokenService> idpTokenServiceSupplier) {
        this.idpTokenServiceSupplier = idpTokenServiceSupplier;
    }

    static OtelBearerTokenSupport resolve() {
        String enabled = System.getenv(OAUTH_ENABLED_ENV);
        if (enabled == null || !Boolean.parseBoolean(enabled.trim())) {
            return null;
        }
        LOGGER.info(
                "OTLP export will authenticate to the Collector with an OAuth2 Bearer token (dpn-service-client, "
                        + "shared with Redis-gating) once PropertyUtil is initialised");
        // NOT built here - see class Javadoc for why. IdpTokenServiceFactory::createRedisIdpTokenService
        // is only actually invoked from idpTokenService(), on first real use.
        return new OtelBearerTokenSupport(IdpTokenServiceFactory::createRedisIdpTokenService);
    }

    /**
     * Builds the {@link IdpTokenService} on first use and memoizes it (double-checked locking -
     * cheap after the first call, and this is only ever on the export path, never per-span/log
     * hot path directly). Safe to call from whatever thread the OTel SDK's background export
     * runs on - by then {@code main()} has long finished its startup sequence and
     * {@code PropertyUtil} is definitely initialised.
     */
    private IdpTokenService idpTokenService() {
        IdpTokenService result = idpTokenService;
        if (result == null) {
            synchronized (this) {
                result = idpTokenService;
                if (result == null) {
                    result = idpTokenServiceSupplier.get();
                    idpTokenService = result;
                }
            }
        }
        return result;
    }

    /**
     * Re-evaluated on every export batch (not baked in once at SDK build time) - it always calls
     * through to {@link IdpTokenService#fetchToken()}, which decides whether to serve a
     * Redis-cached token or fetch a new one, so token refresh is transparent to the exporter.
     */
    private Map<String, String> authorizationHeader() {
        return Map.of(AUTHORIZATION, "Bearer " + idpTokenService().fetchToken());
    }

    SpanExporter customizeSpanExporter(SpanExporter exporter) {
        if (exporter instanceof OtlpHttpSpanExporter otlp) {
            return otlp.toBuilder().setHeaders(this::authorizationHeader).build();
        }
        return exporter;
    }

    MetricExporter customizeMetricExporter(MetricExporter exporter) {
        if (exporter instanceof OtlpHttpMetricExporter otlp) {
            return otlp.toBuilder().setHeaders(this::authorizationHeader).build();
        }
        return exporter;
    }

    LogRecordExporter customizeLogRecordExporter(LogRecordExporter exporter) {
        if (exporter instanceof OtlpHttpLogRecordExporter otlp) {
            return otlp.toBuilder().setHeaders(this::authorizationHeader).build();
        }
        return exporter;
    }
}
