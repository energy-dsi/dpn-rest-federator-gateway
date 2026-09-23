// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.util.UUID;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link DemoParameters} - previously entirely untested.
 * <p>
 * {@code resolve()} itself reads several real environment variables that this session's own
 * manual testing has repeatedly exported in the shell running these tests
 * ({@code ORGANISATION_NAME}, {@code PRODUCT_NAME}, {@code MPAN}, {@code POSTCODE}) - testing it
 * directly risks failing not because of a bug, but because the actual shell genuinely has these
 * set. This test instead exercises the private static helpers via reflection: {@code parseUuid}
 * and {@code optionalUuid} are pure functions with no environment dependency at all, and
 * {@code resolveValue}/{@code resolveFspId} are tested with an {@link org.junit.jupiter.api.Assumptions}
 * guard against the one specific environment variable each reads, skipping rather than failing
 * if that variable happens to already be set.
 * </p>
 */
class DemoParametersTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;

    @AfterEach
    void tearDown() {
        if (propertyUtilMock != null) {
            propertyUtilMock.close();
        }
    }

    private Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = DemoParameters.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // optionalUuid(String) - pure function, no environment dependency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("optionalUuid('') returns null")
    void optionalUuid_blank_returnsNull() throws Exception {
        Object result = invokeStatic("optionalUuid", new Class<?>[] {String.class}, "");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("optionalUuid(whitespace) returns null")
    void optionalUuid_whitespace_returnsNull() throws Exception {
        Object result = invokeStatic("optionalUuid", new Class<?>[] {String.class}, "   ");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("optionalUuid(validUuidString) returns the parsed UUID")
    void optionalUuid_validUuid_returnsParsedUuid() throws Exception {
        String validUuid = "<<your specific value>>";

        Object result = invokeStatic("optionalUuid", new Class<?>[] {String.class}, validUuid);

        assertThat(result).isEqualTo(UUID.fromString(validUuid));
    }

    @Test
    @DisplayName("optionalUuid(invalidString) throws IllegalArgumentException naming ASSET_ID")
    void optionalUuid_invalidUuid_throwsNamingAssetId() {
        assertThatThrownBy(() -> invokeStatic("optionalUuid", new Class<?>[] {String.class}, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASSET_ID")
                .hasMessageContaining("it is omitted")
                .hasMessageContaining("not-a-uuid");
    }

    // ------------------------------------------------------------------
    // parseUuid(name, value) - via resolveFspId(), the only caller that names FSP_ID
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveFspId() generates a random UUID when FSP_ID is genuinely unset")
    void resolveFspId_unset_generatesRandomUuid() throws Exception {
        assumeTrue(System.getenv("FSP_ID") == null, "Skipping: FSP_ID is set in this shell");
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue("federator.rest.demo.fsp.id", ""))
                .thenReturn("");

        Object result = invokeStatic("resolveFspId", new Class<?>[] {});

        assertThat(result).isInstanceOf(UUID.class);
    }

    @Test
    @DisplayName("resolveFspId() throws IllegalArgumentException naming FSP_ID and mentioning random "
            + "generation when the configured value is not a valid UUID")
    void resolveFspId_invalidConfiguredValue_throwsNamingFspId() {
        assumeTrue(System.getenv("FSP_ID") == null, "Skipping: FSP_ID is set in this shell");
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue("federator.rest.demo.fsp.id", ""))
                .thenReturn("not-a-uuid-either");

        assertThatThrownBy(() -> invokeStatic("resolveFspId", new Class<?>[] {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FSP_ID")
                .hasMessageContaining("a random one is generated");
    }

    // ------------------------------------------------------------------
    // resolveValue(envVar, propertyKey, defaultValue) - using a genuinely fake env var name to
    // sidestep any real-environment precedence risk entirely for this specific test
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveValue() returns the PropertyUtil-resolved value when the given env var is unset")
    void resolveValue_envVarUnset_returnsPropertyUtilValue() throws Exception {
        String fakeEnvVar = "DEMO_PARAMETERS_TEST_DEFINITELY_UNSET_VAR_XYZ123";
        assumeTrue(System.getenv(fakeEnvVar) == null, "Skipping: unexpectedly set in this shell");
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue("some.property.key", "some-default"))
                .thenReturn("from-properties-file");

        Object result = invokeStatic(
                "resolveValue",
                new Class<?>[] {String.class, String.class, String.class},
                fakeEnvVar,
                "some.property.key",
                "some-default");

        assertThat(result).isEqualTo("from-properties-file");
    }

    @Test
    @DisplayName("resolveValue() falls back to the default when PropertyUtil throws "
            + "(not initialised, or key absent)")
    void resolveValue_propertyUtilThrows_returnsDefault() throws Exception {
        String fakeEnvVar = "DEMO_PARAMETERS_TEST_DEFINITELY_UNSET_VAR_XYZ123";
        assumeTrue(System.getenv(fakeEnvVar) == null, "Skipping: unexpectedly set in this shell");
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue("some.property.key", "some-default"))
                .thenThrow(new RuntimeException("PropertyUtil not initialised"));

        Object result = invokeStatic(
                "resolveValue",
                new Class<?>[] {String.class, String.class, String.class},
                fakeEnvVar,
                "some.property.key",
                "some-default");

        assertThat(result).isEqualTo("some-default");
    }

    // ------------------------------------------------------------------
    // resolve() - the full public factory. Guarded against every environment variable it reads
    // so this test only runs (rather than gives a false result) when none of them happen to be
    // set in the shell running the build, per the class-level note above.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolve() falls back to defaults for every input when nothing is configured")
    void resolve_nothingConfigured_returnsDefaults() {
        assumeTrue(System.getenv("ORGANISATION_NAME") == null, "Skipping: ORGANISATION_NAME is set");
        assumeTrue(System.getenv("PRODUCT_NAME") == null, "Skipping: PRODUCT_NAME is set");
        assumeTrue(System.getenv("MPAN") == null, "Skipping: MPAN is set");
        assumeTrue(System.getenv("POSTCODE") == null, "Skipping: POSTCODE is set");
        assumeTrue(System.getenv("ASSET_ID") == null, "Skipping: ASSET_ID is set");
        assumeTrue(System.getenv("FSP_ID") == null, "Skipping: FSP_ID is set");
        assumeTrue(System.getenv("SENDER_FMAR_ID") == null, "Skipping: SENDER_FMAR_ID is set");
        assumeTrue(System.getenv("SENDER_ROLE") == null, "Skipping: SENDER_ROLE is set");
        assumeTrue(System.getenv("CONTRACTUAL_AUTHORISATION") == null,
                "Skipping: CONTRACTUAL_AUTHORISATION is set");

        DemoParameters params = DemoParameters.resolve();

        assertThat(params.organisation()).isEmpty();
        assertThat(params.productName()).isEmpty();
        assertThat(params.importMpan()).isEqualTo("1000000000001");
        assertThat(params.postcode()).isEqualTo("SW1A 1AA");
        assertThat(params.assetId()).isNull();
        assertThat(params.fspId()).isNotNull();
        assertThat(params.senderRole()).isEqualTo("FSP");
        assertThat(params.contractualAuthorisation()).isTrue();
    }

    @Test
    @DisplayName("resolveValue() trims whitespace from the PropertyUtil-resolved value")
    void resolveValue_propertyUtilValueHasWhitespace_trimsIt() throws Exception {
        String fakeEnvVar = "DEMO_PARAMETERS_TEST_DEFINITELY_UNSET_VAR_XYZ123";
        assumeTrue(System.getenv(fakeEnvVar) == null, "Skipping: unexpectedly set in this shell");
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue("some.property.key", "some-default"))
                .thenReturn("  padded-value  ");

        Object result = invokeStatic(
                "resolveValue",
                new Class<?>[] {String.class, String.class, String.class},
                fakeEnvVar,
                "some.property.key",
                "some-default");

        assertThat(result).isEqualTo("padded-value");
    }
}
