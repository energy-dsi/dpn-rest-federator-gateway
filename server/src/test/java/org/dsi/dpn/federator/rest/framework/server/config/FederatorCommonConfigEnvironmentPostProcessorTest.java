// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.util.Properties;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Unit tests for {@link FederatorCommonConfigEnvironmentPostProcessor} - previously entirely
 * untested. Uses a real {@link StandardEnvironment} (a plain, cheap-to-construct Spring class -
 * no mock needed) and mocks {@link PropertyUtil} statically for the same reason as other tests
 * in this session: {@code PropertyUtil.initializeProperties()} reads a real environment variable
 * this test cannot control portably.
 */
class FederatorCommonConfigEnvironmentPostProcessorTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private FederatorCommonConfigEnvironmentPostProcessor postProcessor;
    private StandardEnvironment environment;

    @BeforeEach
    void setUp() {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(PropertyUtil::initializeProperties).thenReturn(true);

        postProcessor = new FederatorCommonConfigEnvironmentPostProcessor();
        environment = new StandardEnvironment();
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

    // ------------------------------------------------------------------
    // postProcessEnvironment() - happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("postProcessEnvironment() publishes only the explicitly-listed properties that are actually set")
    void postProcessEnvironment_someListedPropertiesSet_publishesOnlyThose() {
        Properties commonProps = new Properties();
        commonProps.setProperty("idp.keystore.path", "/vault/keystore.jks");
        commonProps.setProperty("idp.jwks.url", "https://idp.example.test/jwks");
        commonProps.setProperty("some.unrelated.property", "should-not-be-published");
        stubCommonConfig(commonProps);

        postProcessor.postProcessEnvironment(environment, null);

        PropertySource<?> published = environment.getPropertySources().get("federatorCommonConfiguration");
        assertThat(published).isNotNull();
        assertThat(published.getProperty("idp.keystore.path")).isEqualTo("/vault/keystore.jks");
        assertThat(published.getProperty("idp.jwks.url")).isEqualTo("https://idp.example.test/jwks");
        assertThat(published.getProperty("some.unrelated.property")).isNull();
    }

    @Test
    @DisplayName("postProcessEnvironment() publishes all six listed properties when all are set")
    void postProcessEnvironment_allListedPropertiesSet_publishesAllSix() {
        Properties commonProps = new Properties();
        commonProps.setProperty("idp.keystore.path", "/vault/keystore.jks");
        commonProps.setProperty("idp.keystore.password", "changeit");
        commonProps.setProperty("idp.truststore.path", "/vault/truststore.jks");
        commonProps.setProperty("idp.truststore.password", "changeit");
        commonProps.setProperty("idp.jwks.url", "https://idp.example.test/jwks");
        commonProps.setProperty("idp.client.id", "test-client-id");
        stubCommonConfig(commonProps);

        postProcessor.postProcessEnvironment(environment, null);

        MapPropertySource published =
                (MapPropertySource) environment.getPropertySources().get("federatorCommonConfiguration");
        assertThat(published).isNotNull();
        assertThat(published.getSource()).hasSize(6);
    }

    @Test
    @DisplayName("postProcessEnvironment() excludes a listed property whose value is blank")
    void postProcessEnvironment_listedPropertyBlank_isExcluded() {
        Properties commonProps = new Properties();
        commonProps.setProperty("idp.keystore.path", "/vault/keystore.jks");
        commonProps.setProperty("idp.jwks.url", "   ");
        stubCommonConfig(commonProps);

        postProcessor.postProcessEnvironment(environment, null);

        PropertySource<?> published = environment.getPropertySources().get("federatorCommonConfiguration");
        assertThat(published).isNotNull();
        assertThat(published.getProperty("idp.jwks.url")).isNull();
        assertThat(published.getProperty("idp.keystore.path")).isEqualTo("/vault/keystore.jks");
    }

    // ------------------------------------------------------------------
    // postProcessEnvironment() - nothing to publish
    // ------------------------------------------------------------------

    @Test
    @DisplayName("postProcessEnvironment() adds no property source when none of the listed properties are set")
    void postProcessEnvironment_noneOfTheListedPropertiesSet_addsNoSource() {
        Properties commonProps = new Properties();
        commonProps.setProperty("completely.unrelated.key", "value");
        stubCommonConfig(commonProps);

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains("federatorCommonConfiguration")).isFalse();
    }

    @Test
    @DisplayName("postProcessEnvironment() adds no property source when the common configuration file is empty")
    void postProcessEnvironment_emptyProperties_addsNoSource() {
        stubCommonConfig(new Properties());

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains("federatorCommonConfiguration")).isFalse();
    }

    // ------------------------------------------------------------------
    // postProcessEnvironment() - the configuration file cannot be loaded at all
    // ------------------------------------------------------------------

    @Test
    @DisplayName("postProcessEnvironment() adds no property source and does not throw when "
            + "PropertyUtil.getPropertiesFromFilePath itself throws")
    void postProcessEnvironment_propertyUtilThrows_addsNoSourceAndDoesNotThrow() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenThrow(new RuntimeException("FEDERATOR_SERVER_PROPERTIES not set"));

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains("federatorCommonConfiguration")).isFalse();
    }

    // ------------------------------------------------------------------
    // Precedence: an existing Environment value must win over the published one (addLast)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A property already present in the Environment (e.g. a -D system property) "
            + "takes precedence over the published value, since the new source is added last")
    void postProcessEnvironment_existingEnvironmentValue_takesPrecedence() {
        System.setProperty("idp.client.id", "from-system-property");
        try {
            Properties commonProps = new Properties();
            commonProps.setProperty("idp.client.id", "from-common-configuration-file");
            stubCommonConfig(commonProps);

            postProcessor.postProcessEnvironment(environment, null);

            assertThat(environment.getProperty("idp.client.id")).isEqualTo("from-system-property");
        } finally {
            System.clearProperty("idp.client.id");
        }
    }

    // ------------------------------------------------------------------
    // getOrder()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getOrder() returns LOWEST_PRECEDENCE, so it runs after Boot's own config-data processing")
    void getOrder_returnsLowestPrecedence() {
        assertThat(postProcessor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
