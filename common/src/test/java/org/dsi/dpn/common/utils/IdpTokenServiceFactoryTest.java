// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.util.Properties;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.service.idp.IdpTokenServiceClientSecretImpl;
import org.dsi.dpn.common.service.idp.IdpTokenServiceMtlsImpl;
import org.dsi.dpn.common.service.idp.IdpTokenServicePrivateJwtImpl;
import org.dsi.dpn.common.service.secret.NoopSecretProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link IdpTokenServiceFactory} - verifies each {@code idp.auth.mode} branch
 * (and the legacy {@code idp.mtls.enabled} fallback) selects the correct {@link IdpTokenService}
 * implementation. {@link PropertyUtil} is mocked via {@link org.mockito.Mockito#mockStatic}
 * (established pattern, see {@code IdpTokenServiceClientSecretImplTest}) so no real properties
 * file, Vault connection, or PropertyUtil singleton initialisation is involved.
 */
class IdpTokenServiceFactoryTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;

    @BeforeEach
    void setUp() {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.createSecretProvider(any(Properties.class)))
                .thenReturn(new NoopSecretProvider());
        propertyUtilMock
                .when(() -> PropertyUtil.overrideWithSecrets(any(Properties.class), any()))
                .thenAnswer(invocation -> null);
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
    }

    private void stubCommonConfig(Properties properties) {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);
    }

    @Test
    @DisplayName("idp.auth.mode=private_key_jwt selects IdpTokenServicePrivateJwtImpl")
    void createIdpTokenService_privateKeyJwtMode_selectsPrivateJwtImpl() {
        Properties properties = new Properties();
        properties.setProperty("idp.auth.mode", "private_key_jwt");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServicePrivateJwtImpl.class);
    }

    @Test
    @DisplayName("idp.auth.mode=mtls selects IdpTokenServiceMtlsImpl (legacy mode)")
    void createIdpTokenService_mtlsMode_selectsMtlsImpl() {
        Properties properties = new Properties();
        properties.setProperty("idp.auth.mode", "mtls");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceMtlsImpl.class);
    }

    @Test
    @DisplayName("idp.auth.mode=client_secret selects IdpTokenServiceClientSecretImpl")
    void createIdpTokenService_clientSecretMode_selectsClientSecretImpl() {
        Properties properties = new Properties();
        properties.setProperty("idp.auth.mode", "client_secret");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceClientSecretImpl.class);
    }

    @Test
    @DisplayName("idp.auth.mode is case-insensitive")
    void createIdpTokenService_modeIsCaseInsensitive() {
        Properties properties = new Properties();
        properties.setProperty("idp.auth.mode", "PRIVATE_KEY_JWT");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServicePrivateJwtImpl.class);
    }

    @Test
    @DisplayName("An unrecognised idp.auth.mode falls back to client_secret")
    void createIdpTokenService_unknownMode_fallsBackToClientSecret() {
        Properties properties = new Properties();
        properties.setProperty("idp.auth.mode", "totally-unknown-mode");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceClientSecretImpl.class);
    }

    @Test
    @DisplayName("idp.auth.mode absent + idp.mtls.enabled=true falls back to the legacy mTLS mode")
    void createIdpTokenService_authModeAbsent_legacyMtlsEnabledTrue_selectsMtlsImpl() {
        Properties properties = new Properties();
        properties.setProperty("idp.mtls.enabled", "true");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceMtlsImpl.class);
    }

    @Test
    @DisplayName("idp.auth.mode absent + idp.mtls.enabled=false (or absent) falls back to client_secret")
    void createIdpTokenService_authModeAbsent_legacyMtlsEnabledFalse_selectsClientSecretImpl() {
        Properties properties = new Properties();
        // Neither idp.auth.mode nor idp.mtls.enabled configured at all.
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceClientSecretImpl.class);
    }

    @Test
    @DisplayName("idp.auth.mode blank falls back to the legacy idp.mtls.enabled path, not the unrecognised-mode path")
    void createIdpTokenService_authModeBlank_usesLegacyFallback() {
        Properties properties = new Properties();
        properties.setProperty("idp.auth.mode", "   ");
        properties.setProperty("idp.mtls.enabled", "true");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceMtlsImpl.class);
    }

    @Test
    @DisplayName("createRedisIdpTokenService always returns a client_secret-mode implementation with the redis.idp. prefix")
    void createRedisIdpTokenService_alwaysClientSecretMode() {
        Properties properties = new Properties();
        properties.setProperty("redis.idp.token.url", "https://idp.example.test/token");
        properties.setProperty("redis.idp.client.id", "redis-gate-client");
        properties.setProperty("redis.idp.client.secret", "redis-gate-secret");
        stubCommonConfig(properties);

        IdpTokenService service = IdpTokenServiceFactory.createRedisIdpTokenService();

        assertThat(service).isInstanceOf(IdpTokenServiceClientSecretImpl.class);
    }

    @Test
    @DisplayName("IdpTokenServiceFactory cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = IdpTokenServiceFactory.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(constructor::newInstance)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
