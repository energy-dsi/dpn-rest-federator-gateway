// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.dsi.dpn.common.utils.IdpTokenServiceFactory;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Covers the public no-arg {@link RestClient#RestClient()} constructor - previously entirely
 * untested, since every other test in this suite uses the package-visible testing constructor.
 *
 * <p>Mocks the static singletons the constructor touches directly ({@code PropertyUtil},
 * {@code IdpTokenServiceFactory}) and, since the constructor also builds a real
 * {@code OcspClientVerificationServiceImpl} inline (not overridable), mocks
 * {@code VaultTlsSupport} so that construction succeeds without real keystore/truststore files.
 * Overrides {@code bootstrap()} and {@code startCertWatcher()} on a subclass so no real
 * Management Node call or file-watcher thread is required - those two methods already have
 * dedicated real-code coverage in {@code RestClientBootstrapTest}.
 */
class RestClientConstructorTest {

    static class NoopClient extends RestClient {
        boolean bootstrapCalled;
        boolean watcherCalled;

        @Override
        protected void bootstrap() {
            bootstrapCalled = true;
        }

        @Override
        protected void startCertWatcher() {
            watcherCalled = true;
        }
    }

    @Test
    @DisplayName("public RestClient() initialises commonProps/idpTokenService/ocspService/timeout "
            + "from PropertyUtil and IdpTokenServiceFactory, then calls bootstrap() and startCertWatcher()")
    void constructor_initialisesAndBootstraps() throws Exception {
        Properties props = new Properties();
        props.setProperty("management.node.base.url", "https://mn.test");

        IdpTokenService idpTokenService = mock(IdpTokenService.class);

        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, null, null);

        try (MockedStatic<PropertyUtil> propertyUtil = mockStatic(PropertyUtil.class);
                MockedStatic<IdpTokenServiceFactory> idpFactory = mockStatic(IdpTokenServiceFactory.class);
                MockedStatic<VaultTlsSupport> vaultTlsSupport = mockStatic(VaultTlsSupport.class)) {

            propertyUtil
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(props);
            propertyUtil
                    .when(() -> PropertyUtil.getPropertyValue("federator.rest.read.timeout.seconds", "60"))
                    .thenReturn("45");
            propertyUtil
                    .when(() -> PropertyUtil.getPropertyValue("management.node.base.url", ""))
                    .thenReturn("https://mn.test");
            idpFactory.when(IdpTokenServiceFactory::createIdpTokenService).thenReturn(idpTokenService);
            vaultTlsSupport.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(true);
            vaultTlsSupport.when(VaultTlsSupport::sslContext).thenReturn(trustAll);

            NoopClient client = new NoopClient();

            assertThat(client.bootstrapCalled).isTrue();
            assertThat(client.watcherCalled).isTrue();
            assertThat(client.getAllRegistrations()).isEmpty();
        }
    }
}
