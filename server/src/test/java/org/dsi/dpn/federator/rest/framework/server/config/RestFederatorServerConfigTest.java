// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.Path;
import java.util.Properties;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.management.ManagementNodeDataHandler;
import org.dsi.dpn.common.service.config.ProducerConfigService;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.utils.IdpTokenServiceFactory;
import org.dsi.dpn.common.utils.KeycloakSessionGuard;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Unit tests for {@link RestFederatorServerConfig} - previously entirely untested (4% coverage;
 * this class contains its own {@code static} initialiser calling
 * {@link PropertyUtil#initializeProperties()}, which reads a real environment variable, so it
 * had never been safely exercisable in a plain unit test).
 * <p>
 * {@link PropertyUtil}, {@link IdpTokenServiceFactory}, and {@link KeycloakSessionGuard} are all
 * mocked statically <b>before this test ever first references {@code RestFederatorServerConfig}
 * itself</b> - Java only runs a class's {@code static} initialiser on first genuine use, so as
 * long as the {@link PropertyUtil} mock is already active at that point, the real environment
 * variable check inside {@code initializeProperties()} is never reached.
 * </p>
 */
class RestFederatorServerConfigTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private MockedStatic<IdpTokenServiceFactory> idpTokenServiceFactoryMock;
    private MockedStatic<KeycloakSessionGuard> keycloakSessionGuardMock;

    @BeforeEach
    void setUp() {
        // Any unstubbed PropertyUtil.getPropertyValue(key, defaultValue) call - used directly
        // by ManagementNodeDataHandler's constructor for its base URL and timeout - returns
        // the CALLER's own defaultValue argument, exactly as the real method does when a
        // property is genuinely absent. Without this, a plain mockStatic() with no default
        // answer returns null for any unstubbed String-returning call, which fails inside
        // normalizeUrl(null) rather than surfacing anything useful.
        org.mockito.stubbing.Answer<Object> defaultToSecondArgument = invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length == 2 && args[1] instanceof String defaultValue
                    && "getPropertyValue".equals(invocation.getMethod().getName())) {
                return defaultValue;
            }
            return null;
        };
        propertyUtilMock = mockStatic(PropertyUtil.class, defaultToSecondArgument);
        propertyUtilMock.when(PropertyUtil::initializeProperties).thenReturn(true);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(new Properties());

        idpTokenServiceFactoryMock = mockStatic(IdpTokenServiceFactory.class);
        keycloakSessionGuardMock = mockStatic(KeycloakSessionGuard.class);
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
        idpTokenServiceFactoryMock.close();
        keycloakSessionGuardMock.close();
    }

    private RestFederatorServerConfig newConfig() {
        // First genuine reference to the class in this test run - this is what triggers its
        // static initialiser, safely, against the PropertyUtil mock configured above.
        return new RestFederatorServerConfig();
    }

    // ------------------------------------------------------------------
    // Static initialiser
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Constructing the config class succeeds when PropertyUtil.initializeProperties() reports success")
    void staticInitialiser_propertyUtilSucceeds_constructsWithoutError() {
        assertThat(newConfig()).isNotNull();
    }

    // ------------------------------------------------------------------
    // idpTokenService() / redisIdpTokenService()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("idpTokenService() delegates to IdpTokenServiceFactory.createIdpTokenService()")
    void idpTokenService_delegatesToFactory() {
        IdpTokenService fakeService = mock(IdpTokenService.class);
        idpTokenServiceFactoryMock.when(IdpTokenServiceFactory::createIdpTokenService).thenReturn(fakeService);

        IdpTokenService result = newConfig().idpTokenService();

        assertThat(result).isSameAs(fakeService);
    }

    @Test
    @DisplayName("redisIdpTokenService() delegates to IdpTokenServiceFactory.createRedisIdpTokenService() "
            + "and configures KeycloakSessionGuard with its fetchToken method")
    void redisIdpTokenService_delegatesToFactoryAndConfiguresGuard() {
        IdpTokenService fakeRedisIdpService = mock(IdpTokenService.class);
        idpTokenServiceFactoryMock
                .when(IdpTokenServiceFactory::createRedisIdpTokenService)
                .thenReturn(fakeRedisIdpService);

        IdpTokenService result = newConfig().redisIdpTokenService();

        assertThat(result).isSameAs(fakeRedisIdpService);
        keycloakSessionGuardMock.verify(() -> KeycloakSessionGuard.configure(org.mockito.ArgumentMatchers.any()));
    }

    // ------------------------------------------------------------------
    // managementNodeDataHandler() / producerConfigService() / ocspVerificationService()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("managementNodeDataHandler() builds a real, non-null handler wired with the given IdpTokenService")
    void managementNodeDataHandler_buildsRealHandler() {
        IdpTokenService fakeService = mock(IdpTokenService.class);

        ManagementNodeDataHandler handler = newConfig().managementNodeDataHandler(fakeService);

        assertThat(handler).isNotNull();
    }

    @Test
    @DisplayName("producerConfigService() builds a real, non-null service wired with the given handler")
    void producerConfigService_buildsRealService() {
        ManagementNodeDataHandler fakeHandler = mock(ManagementNodeDataHandler.class);

        ProducerConfigService service = newConfig().producerConfigService(fakeHandler);

        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("ocspVerificationService() builds a real, non-null OCSP verification service")
    void ocspVerificationService_buildsRealService() {
        IdpTokenService fakeService = mock(IdpTokenService.class);
        // OcspVerificationServiceImpl's constructor eagerly builds a real HttpClient via
        // HttpClientFactoryUtils, which genuinely throws given this test's unconfigured
        // Properties (no idp.keystore.path etc.) - HttpClientFactoryUtils already has its own
        // dedicated test file, so this isolates the present test to just this bean's own
        // wiring, rather than re-exercising that class's already-tested internals.
        try (MockedStatic<org.dsi.dpn.common.utils.HttpClientFactoryUtils> httpClientFactoryUtilsMock =
                mockStatic(org.dsi.dpn.common.utils.HttpClientFactoryUtils.class)) {
            httpClientFactoryUtilsMock
                    .when(() -> org.dsi.dpn.common.utils.HttpClientFactoryUtils.createHttpClientWithMtls(
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            OcspVerificationService service = newConfig().ocspVerificationService(fakeService);

            assertThat(service).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // backendRestTemplate() - the branch-rich bean: plain HTTP vs TLS-trusting, and the
    // PKCS12-first-then-JKS-fallback truststore loading documented on loadTrustStore().
    // ------------------------------------------------------------------

    @Test
    @DisplayName("backendRestTemplate() returns a plain-HTTP RestTemplate when vault.truststore.path is unset")
    void backendRestTemplate_noTruststorePath_returnsPlainHttpTemplate() {
        // The default empty Properties from setUp() has no vault.truststore.path set.
        RestTemplate template = newConfig().backendRestTemplate();

        assertThat(template).isNotNull();
        assertThat(template.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("backendRestTemplate() returns a plain-HTTP RestTemplate when vault.truststore.path is blank")
    void backendRestTemplate_blankTruststorePath_returnsPlainHttpTemplate() {
        Properties properties = new Properties();
        properties.setProperty("vault.truststore.path", "   ");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);

        RestTemplate template = newConfig().backendRestTemplate();

        assertThat(template).isNotNull();
    }

    @Test
    @DisplayName("backendRestTemplate() trusts the backend via a genuinely JKS-format truststore")
    void backendRestTemplate_genuineJksTruststore_buildsTlsTrustingTemplate(@TempDir Path tempDir) throws Exception {
        Path jksFile = generateKeystore(tempDir, "truststore.jks", "JKS");
        stubTruststoreProperties(jksFile, "changeit");

        RestTemplate template = newConfig().backendRestTemplate();

        assertThat(template).isNotNull();
    }

    @Test
    @DisplayName("backendRestTemplate() trusts the backend via a PKCS12-format truststore named '.jks' "
            + "(the real-world case documented on loadTrustStore: the cert manager writes PKCS12 "
            + "content despite the .jks filename)")
    void backendRestTemplate_pkcs12NamedJksTruststore_buildsTlsTrustingTemplate(@TempDir Path tempDir)
            throws Exception {
        Path misnamedFile = generateKeystore(tempDir, "truststore.jks", "PKCS12");
        stubTruststoreProperties(misnamedFile, "changeit");

        RestTemplate template = newConfig().backendRestTemplate();

        assertThat(template).isNotNull();
    }

    @Test
    @DisplayName("backendRestTemplate() throws FederatorSslException when the configured truststore "
            + "file cannot be loaded as either PKCS12 or JKS")
    void backendRestTemplate_missingTruststoreFile_throwsFederatorSslException(@TempDir Path tempDir) {
        stubTruststoreProperties(tempDir.resolve("does-not-exist.jks"), "changeit");

        assertThatThrownBy(() -> newConfig().backendRestTemplate())
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to build backend RestTemplate SSLContext");
    }

    @Test
    @DisplayName("backendRestTemplate() throws FederatorSslException when the truststore file exists "
            + "but is neither valid PKCS12 nor valid JKS content")
    void backendRestTemplate_corruptTruststoreFile_throwsFederatorSslException(@TempDir Path tempDir)
            throws Exception {
        Path corruptFile = tempDir.resolve("corrupt.jks");
        java.nio.file.Files.write(corruptFile, "not a keystore".getBytes());
        stubTruststoreProperties(corruptFile, "changeit");

        assertThatThrownBy(() -> newConfig().backendRestTemplate())
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to build backend RestTemplate SSLContext");
    }

    private void stubTruststoreProperties(Path truststorePath, String password) {
        Properties properties = new Properties();
        properties.setProperty("vault.truststore.path", truststorePath.toString());
        properties.setProperty("vault.truststore.password", password);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);
    }

    /**
     * Runs {@code keytool -genkeypair}, producing a keystore containing one self-signed
     * certificate, in the requested storetype, at the requested path - the same technique used
     * in {@code SSLUtilsTest} to reproduce the real "PKCS12 content under a .jks name" scenario
     * without any new test dependency.
     */
    private Path generateKeystore(Path dir, String fileName, String storeType) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "backend-truststore-test",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=rest-federator-server-config-test",
                        "-storetype",
                        storeType,
                        "-keystore",
                        file.toString(),
                        "-storepass",
                        "changeit",
                        "-keypass",
                        "changeit")
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "keytool failed (exit " + exitCode + "): "
                            + new String(process.getInputStream().readAllBytes()));
        }
        return file;
    }
}
