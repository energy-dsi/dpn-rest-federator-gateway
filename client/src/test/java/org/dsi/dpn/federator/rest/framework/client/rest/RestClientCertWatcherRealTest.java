// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Exercises the real body of {@link RestClient#startCertWatcher()}'s watcher thread - the
 * successful-rebuild path, the {@code InterruptedException} shutdown path, and the generic
 * {@code Exception} shutdown path (e.g. {@code buildWebClient()} failing mid-rotation).
 *
 * <p>Every other test touching the cert watcher either checks the "not started" short-circuits
 * (null/blank/missing-directory keystore path) or uses a fake watcher implementation. This class
 * runs the genuine watcher thread against a real temp-directory {@code WatchService}, so the
 * actual file-change handling, exception handling and rebuild logic are exercised and counted.
 */
class RestClientCertWatcherRealTest {

    private static final ProductKey KEY = new ProductKey("Org", "Product");

    private static RestClient.ProductRegistration reg(WebClient webClient) {
        return new RestClient.ProductRegistration(
                "Org", "prod-id", "Product", "https://host:8443",
                List.of(new AllowedPath("GET", "/api/v1/data")), webClient);
    }

    private static void setKeystorePath(RestClient client, String path) throws Exception {
        Field f = RestClient.class.getDeclaredField("commonProps");
        f.setAccessible(true);
        Properties props = new Properties();
        props.setProperty("idp.keystore.path", path);
        f.set(client, props);
    }

    /**
     * Watcher threads from earlier tests in this class are daemon threads that are never
     * explicitly stopped, so several can be alive at once, all named
     * {@code "rest-client-cert-watcher"}. To reliably identify the thread THIS test just
     * started (rather than picking up a stale one left running by an earlier test method in
     * the same JVM), snapshot the existing threads with that name before calling
     * {@code startCertWatcher()} and diff against the snapshot taken after.
     */
    private static Set<Thread> watcherThreadsSnapshot() {
        Set<Thread> snapshot = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("rest-client-cert-watcher".equals(t.getName())) {
                snapshot.add(t);
            }
        }
        return snapshot;
    }

    private static Thread newWatcherThread(Set<Thread> before) {
        for (Thread t : watcherThreadsSnapshot()) {
            if (!before.contains(t)) {
                return t;
            }
        }
        return null;
    }

    @Test
    @DisplayName("startCertWatcher() rebuilds the WebClient and replaces it in every registration "
            + "when the watched P12 file is modified")
    void certWatcher_rebuildsOnFileChange(@TempDir Path tempDir) throws Exception {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        OcspClientVerificationService ocspService = mock(OcspClientVerificationService.class);
        WebClient original = mock(WebClient.class);
        WebClient rebuilt = mock(WebClient.class);

        Path p12 = tempDir.resolve("identity.p12");
        Files.writeString(p12, "dummy", StandardOpenOption.CREATE);

        class RebuildingClient extends RestClient {
            RebuildingClient() {
                super(Map.of(KEY, reg(original)), idpTokenService, ocspService, Duration.ofSeconds(5));
            }

            @Override
            protected WebClient buildWebClient() {
                return rebuilt;
            }
        }

        RebuildingClient client = new RebuildingClient();
        setKeystorePath(client, p12.toString());

        client.startCertWatcher();
        Thread.sleep(300);

        Files.writeString(p12, "rotated-cert-bytes", StandardOpenOption.TRUNCATE_EXISTING);

        WebClient current = original;
        long deadline = System.currentTimeMillis() + 5000;
        while (current != rebuilt && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            current = client.getRegistration(KEY).orElseThrow().webClient();
        }

        assertThat(client.getRegistration(KEY).orElseThrow().webClient()).isSameAs(rebuilt);
    }

    @Test
    @DisplayName("startCertWatcher() thread exits cleanly when interrupted while blocked on watcher.take()")
    void certWatcher_interruptedExitsCleanly(@TempDir Path tempDir) throws Exception {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        OcspClientVerificationService ocspService = mock(OcspClientVerificationService.class);
        WebClient webClient = mock(WebClient.class);

        Path p12 = tempDir.resolve("identity.p12");
        Files.writeString(p12, "dummy", StandardOpenOption.CREATE);

        RestClient client = new RestClient(
                Map.of(KEY, reg(webClient)), idpTokenService, ocspService, Duration.ofSeconds(5));
        setKeystorePath(client, p12.toString());

        Set<Thread> before = watcherThreadsSnapshot();
        client.startCertWatcher();
        Thread.sleep(300);

        Thread watcherThread = newWatcherThread(before);
        assertThat(watcherThread).as("watcher thread should have started").isNotNull();

        watcherThread.interrupt();
        watcherThread.join(3000);

        assertThat(watcherThread.isAlive()).isFalse();
    }

    @Test
    @DisplayName("startCertWatcher() logs and exits the loop when rebuilding the WebClient throws")
    void certWatcher_buildWebClientThrows_exitsLoop(@TempDir Path tempDir) throws Exception {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        OcspClientVerificationService ocspService = mock(OcspClientVerificationService.class);
        WebClient webClient = mock(WebClient.class);

        Path p12 = tempDir.resolve("identity.p12");
        Files.writeString(p12, "dummy", StandardOpenOption.CREATE);

        class ThrowingClient extends RestClient {
            ThrowingClient() {
                super(Map.of(KEY, reg(webClient)), idpTokenService, ocspService, Duration.ofSeconds(5));
            }

            @Override
            protected WebClient buildWebClient() {
                throw new RuntimeException("boom - simulated buildWebClient failure");
            }
        }

        ThrowingClient client = new ThrowingClient();
        setKeystorePath(client, p12.toString());

        Set<Thread> before = watcherThreadsSnapshot();
        client.startCertWatcher();
        Thread.sleep(300);

        Thread watcherThread = newWatcherThread(before);
        assertThat(watcherThread).as("watcher thread should have started").isNotNull();

        Files.writeString(p12, "rotated", StandardOpenOption.TRUNCATE_EXISTING);
        watcherThread.join(5000);

        assertThat(watcherThread.isAlive())
                .as("watcher thread should have terminated after buildWebClient() threw")
                .isFalse();
    }
}
