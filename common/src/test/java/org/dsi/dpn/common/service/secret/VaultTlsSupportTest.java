// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Properties;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link VaultTlsSupport}.
 * <p>
 * {@code keyManagers()}/{@code trustManagers()}/{@code sslContext()} lazily initialise and
 * cache a process-wide {@code ReloadableX509KeyManager}/{@code ReloadableX509TrustManager} pair
 * the very first time they succeed - a genuine, permanent (per-JVM) side effect this test must
 * never trigger, or it would leak Vault-shaped state into every other test in this module. Since
 * no live Vault is available, every exercised call here is a *failure* path (missing Vault
 * material via a real {@link NoopSecretProvider}), which by construction never reaches - let
 * alone populates - that cache. The class's other, pure static logic (interval parsing, the
 * X509 manager type filters, the ephemeral password generator, the utility-class constructor
 * guard) is exercised directly via reflection.
 * </p>
 */
class VaultTlsSupportTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;

    private void mockCommonConfig(Properties properties) {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);
        propertyUtilMock.when(() -> PropertyUtil.createSecretProvider(any())).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        if (propertyUtilMock != null) {
            propertyUtilMock.close();
        }
    }

    // ------------------------------------------------------------------
    // isVaultTlsEnabled()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isVaultTlsEnabled defaults to true when the property is absent")
    void isVaultTlsEnabled_defaultsTrue() {
        mockCommonConfig(new Properties());

        assertThat(VaultTlsSupport.isVaultTlsEnabled()).isTrue();
    }

    @Test
    @DisplayName("isVaultTlsEnabled honours an explicit 'false'")
    void isVaultTlsEnabled_explicitFalse() {
        Properties props = new Properties();
        props.setProperty(VaultTlsSupport.VAULT_TLS_ENABLED, "false");
        mockCommonConfig(props);

        assertThat(VaultTlsSupport.isVaultTlsEnabled()).isFalse();
    }

    @Test
    @DisplayName("isVaultTlsEnabled honours an explicit 'true'")
    void isVaultTlsEnabled_explicitTrue() {
        Properties props = new Properties();
        props.setProperty(VaultTlsSupport.VAULT_TLS_ENABLED, "true");
        mockCommonConfig(props);

        assertThat(VaultTlsSupport.isVaultTlsEnabled()).isTrue();
    }

    // ------------------------------------------------------------------
    // keyManagers() / trustManagers() / sslContext() - failure path only (see class javadoc)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keyManagers throws FederatorSslException when no Vault is configured (NoopSecretProvider)")
    void keyManagers_noVaultConfigured_throwsFederatorSslException() {
        mockCommonConfig(new Properties());

        assertThatThrownBy(VaultTlsSupport::keyManagers)
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Vault did not return leaf certificate");
    }

    @Test
    @DisplayName("trustManagers throws FederatorSslException when no Vault is configured (NoopSecretProvider)")
    void trustManagers_noVaultConfigured_throwsFederatorSslException() {
        mockCommonConfig(new Properties());

        assertThatThrownBy(VaultTlsSupport::trustManagers).isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("sslContext propagates the same FederatorSslException as keyManagers, unwrapped")
    void sslContext_noVaultConfigured_propagatesFederatorSslException() {
        mockCommonConfig(new Properties());

        assertThatThrownBy(VaultTlsSupport::sslContext)
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Vault did not return leaf certificate");
    }

    // ------------------------------------------------------------------
    // Private static helpers, via reflection
    // ------------------------------------------------------------------

    private static Object invokeStatic(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = VaultTlsSupport.class.getDeclaredMethod(name, paramTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("firstX509KeyManager returns the first X509KeyManager in the array")
    void firstX509KeyManager_returnsMatchingManager() throws Exception {
        X509KeyManager x509 = mock(X509KeyManager.class);
        KeyManager[] managers = {mock(KeyManager.class), x509};

        Object result = invokeStatic("firstX509KeyManager", new Class<?>[] {KeyManager[].class}, (Object) managers);

        assertThat(result).isSameAs(x509);
    }

    @Test
    @DisplayName("firstX509KeyManager throws FederatorSslException when no X509KeyManager is present")
    void firstX509KeyManager_noneFound_throws() {
        KeyManager[] managers = {mock(KeyManager.class)};

        assertThatThrownBy(() ->
                        invokeStatic("firstX509KeyManager", new Class<?>[] {KeyManager[].class}, (Object) managers))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("No X509KeyManager");
    }

    @Test
    @DisplayName("firstX509TrustManager returns the first X509TrustManager in the array")
    void firstX509TrustManager_returnsMatchingManager() throws Exception {
        X509TrustManager x509 = mock(X509TrustManager.class);
        TrustManager[] managers = {mock(TrustManager.class), x509};

        Object result =
                invokeStatic("firstX509TrustManager", new Class<?>[] {TrustManager[].class}, (Object) managers);

        assertThat(result).isSameAs(x509);
    }

    @Test
    @DisplayName("firstX509TrustManager throws FederatorSslException when no X509TrustManager is present")
    void firstX509TrustManager_noneFound_throws() {
        TrustManager[] managers = {mock(TrustManager.class)};

        assertThatThrownBy(() -> invokeStatic(
                        "firstX509TrustManager", new Class<?>[] {TrustManager[].class}, (Object) managers))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("No X509TrustManager");
    }

    @Test
    @DisplayName("ephemeralPassword produces a fresh, non-empty password on every call")
    void ephemeralPassword_producesFreshValues() throws Exception {
        char[] first = (char[]) invokeStatic("ephemeralPassword", new Class<?>[0]);
        char[] second = (char[]) invokeStatic("ephemeralPassword", new Class<?>[0]);

        assertThat(first).isNotEmpty();
        assertThat(new String(first)).isNotEqualTo(new String(second));
    }

    @Test
    @DisplayName("parseIntervalSeconds reads a configured numeric value")
    void parseIntervalSeconds_readsConfiguredValue() throws Exception {
        Properties props = new Properties();
        props.setProperty(VaultTlsSupport.VAULT_TLS_RELOAD_INTERVAL_SECONDS, "120");
        mockCommonConfig(props);

        long result = (long) invokeStatic("parseIntervalSeconds", new Class<?>[0]);

        assertThat(result).isEqualTo(120L);
    }

    @Test
    @DisplayName("parseIntervalSeconds falls back to the 3600s default on an unparsable value")
    void parseIntervalSeconds_invalidValue_fallsBackToDefault() throws Exception {
        Properties props = new Properties();
        props.setProperty(VaultTlsSupport.VAULT_TLS_RELOAD_INTERVAL_SECONDS, "not-a-number");
        mockCommonConfig(props);

        long result = (long) invokeStatic("parseIntervalSeconds", new Class<?>[0]);

        assertThat(result).isEqualTo(3600L);
    }

    @Test
    @DisplayName("commonConfig swallows a PropertyUtil failure and returns an empty Properties")
    void commonConfig_propertyUtilThrows_returnsEmptyProperties() throws Exception {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenThrow(new RuntimeException("no config file in this test context"));

        Properties result = (Properties) invokeStatic("commonConfig", new Class<?>[0]);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("VaultTlsSupport cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = VaultTlsSupport.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
