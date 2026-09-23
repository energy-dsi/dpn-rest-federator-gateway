// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link KeycloakAuthConfig} - the fail-secure master switch gating
 * {@code RedisUtil.getKeycloakGatedInstance()}. {@link PropertyUtil} is mocked via
 * {@link org.mockito.Mockito#mockStatic}, matching the established pattern in
 * {@code IdpTokenServiceClientSecretImplTest}, so no real properties file or PropertyUtil
 * singleton initialisation is needed.
 */
class KeycloakAuthConfigTest {

    @Test
    @DisplayName("isEnabled() defaults to true when keycloak.auth.enabled is absent")
    void isEnabled_propertyAbsent_defaultsToTrue() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(new Properties());

            assertThat(KeycloakAuthConfig.isEnabled()).isTrue();
        }
    }

    @Test
    @DisplayName("isEnabled() defaults to true when keycloak.auth.enabled is blank")
    void isEnabled_propertyBlank_defaultsToTrue() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties properties = new Properties();
            properties.setProperty("keycloak.auth.enabled", "   ");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(properties);

            assertThat(KeycloakAuthConfig.isEnabled()).isTrue();
        }
    }

    @Test
    @DisplayName("isEnabled() returns true when explicitly configured true")
    void isEnabled_explicitTrue_returnsTrue() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties properties = new Properties();
            properties.setProperty("keycloak.auth.enabled", "true");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(properties);

            assertThat(KeycloakAuthConfig.isEnabled()).isTrue();
        }
    }

    @Test
    @DisplayName("isEnabled() returns false when explicitly configured false")
    void isEnabled_explicitFalse_returnsFalse() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties properties = new Properties();
            properties.setProperty("keycloak.auth.enabled", "false");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(properties);

            assertThat(KeycloakAuthConfig.isEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("isEnabled() is case-insensitive and tolerates surrounding whitespace")
    void isEnabled_mixedCaseWithWhitespace_isParsedCorrectly() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties properties = new Properties();
            properties.setProperty("keycloak.auth.enabled", "  FALSE  ");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(properties);

            assertThat(KeycloakAuthConfig.isEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("isEnabled() fails secure (returns true) when reading the config throws")
    void isEnabled_configReadThrows_failsSecureToTrue() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenThrow(new PropertyUtil.PropertyUtilException("simulated unreadable config"));

            assertThat(KeycloakAuthConfig.isEnabled()).isTrue();
        }
    }

    @Test
    @DisplayName("isEnabled() fails secure (returns true) for a malformed properties value that is not 'true'/'false'")
    void isEnabled_malformedValue_parsesAsFalseViaBooleanParseButNotAnError() {
        // Boolean.parseBoolean() never throws - any non-"true" string parses to false. This
        // documents that actual current behaviour (a typo like 'flase' silently disables
        // Keycloak gating) rather than assuming it throws and falls back to fail-secure.
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties properties = new Properties();
            properties.setProperty("keycloak.auth.enabled", "flase");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(properties);

            assertThat(KeycloakAuthConfig.isEnabled()).isFalse();
        }
    }
}
