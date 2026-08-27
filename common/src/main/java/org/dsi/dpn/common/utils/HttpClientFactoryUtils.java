// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.dsi.dpn.common.exception.FederatorSslException;

public class HttpClientFactoryUtils {

    private static final int HTTP_TIMEOUT = 10;

    private HttpClientFactoryUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static HttpClient createHttpClientWithMtls(Properties properties) {
        try {
            SSLContext sslContext;
            if (VaultTlsSupport.isVaultTlsEnabled()) {
                // IDP mTLS identity/trust sourced from Vault (no keystore files on disk).
                sslContext = VaultTlsSupport.sslContext();
            } else {
                String keystorePath = properties.getProperty("idp.keystore.path");
                String keystorePassword = properties.getProperty("idp.keystore.password");
                String truststorePath = properties.getProperty("idp.truststore.path");
                String truststorePassword = properties.getProperty("idp.truststore.password");

                sslContext =
                        SSLUtils.createSSLContext(keystorePath, keystorePassword, truststorePath, truststorePassword);
            }

            // Pinned for the same reason the OCSP clients pin it: java.net.http.HttpClient
            // defaults to HTTP_2 and negotiates it via ALPN if the far side (Keycloak)
            // advertises it, which this codebase doesn't otherwise rely on anywhere.
            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

        } catch (Exception e) {
            throw new FederatorSslException("Failed to create HttpClient with SSL context", e);
        }
    }

    public static HttpClient createHttpClient(Properties properties) {
        try {
            String truststorePath = properties.getProperty("idp.truststore.path");
            String truststorePassword = properties.getProperty("idp.truststore.password");
            SSLContext sslContext = SSLUtils.createSSLContextWithTrustStore(truststorePath, truststorePassword);
            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        } catch (Exception e) {
            throw new FederatorSslException("Failed to create HttpClient", e);
        }
    }
}
