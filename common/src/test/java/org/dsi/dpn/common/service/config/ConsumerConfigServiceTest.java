// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.dsi.dpn.common.management.ManagementNodeDataException;
import org.dsi.dpn.common.management.ManagementNodeDataHandler;
import org.dsi.dpn.common.model.dto.ConsumerConfigDTO;
import org.dsi.dpn.common.storage.InMemoryConfigurationStore;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.ResilienceSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link ConsumerConfigService} (9% coverage) which, together with
 * {@link ProducerConfigService}, is also the vehicle used to exercise the default cache/fetch
 * logic declared on {@link ConfigService} (there is nowhere to unit test an interface's default
 * methods except through a concrete implementation).
 * <p>
 * {@link PropertyUtil} is mocked statically (as in
 * {@code IdpTokenServiceClientSecretImplTest}) both for the constructor's
 * {@code federator.consumer.id} lookup and for the Resilience4j configuration read internally by
 * {@link ResilienceSupport} when {@link ConfigService#fetchWithResilience()} runs; retry attempts
 * are forced to 1 so failure-path tests don't incur real retry backoff delays.
 * </p>
 */
class ConsumerConfigServiceTest {

    private static final String CONSUMER_ID_PROP = "federator.consumer.id";

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private ManagementNodeDataHandler dataHandler;
    private InMemoryConfigurationStore configStore;

    @BeforeEach
    void setUp() {
        ResilienceSupport.clearForTests();

        // Any unstubbed PropertyUtil.getProperty*Value(key, defaultValue) call - used
        // internally by ResilienceSupport for retry/circuit-breaker config - returns the
        // caller's own defaultValue argument, parsed to the correct type.
        Answer<Object> defaultToSecondArgument = invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length == 2 && args[1] instanceof String defaultValue) {
                return switch (invocation.getMethod().getName()) {
                    case "getPropertyIntValue" -> Integer.parseInt(defaultValue);
                    case "getPropertyLongValue" -> Long.parseLong(defaultValue);
                    case "getPropertyDurationValue" -> Duration.parse(defaultValue);
                    default -> defaultValue;
                };
            }
            return null;
        };
        propertyUtilMock = mockStatic(PropertyUtil.class, defaultToSecondArgument);
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(CONSUMER_ID_PROP, null)).thenReturn("consumer-1");
        // Forces exactly one attempt - without this, failure-path tests genuinely retry with
        // real exponential backoff before the exception is finally rethrown.
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(1);

        dataHandler = mock(ManagementNodeDataHandler.class);
        configStore = mock(InMemoryConfigurationStore.class);
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
        ResilienceSupport.clearForTests();
    }

    private ConsumerConfigService newService() {
        return new ConsumerConfigService(dataHandler, configStore);
    }

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects a null data handler")
    void constructor_nullDataHandler_throwsNullPointerException() {
        assertThatThrownBy(() -> new ConsumerConfigService(null, configStore)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects a null config store")
    void constructor_nullConfigStore_throwsNullPointerException() {
        assertThatThrownBy(() -> new ConsumerConfigService(dataHandler, null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------
    // Contract accessors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("contract accessors expose the consumer-specific prefix, id, and DTO type")
    void contractAccessors_exposeConsumerSpecifics() {
        ConsumerConfigService service = newService();

        assertThat(service.getConfigStore()).isSameAs(configStore);
        assertThat(service.getKeyPrefix()).isEqualTo("consumer:");
        assertThat(service.getConfiguredClientId()).isEqualTo("consumer-1");
        assertThat(service.getDtoClass()).isEqualTo(ConsumerConfigDTO.class);
    }

    @Test
    @DisplayName("getConfiguredClientId is null and buildCacheKey falls back to 'default' when unconfigured")
    void getConfiguredClientId_propertyAbsent_fallsBackToDefaultCacheKey() {
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(CONSUMER_ID_PROP, null)).thenReturn(null);

        ConsumerConfigService service = newService();

        assertThat(service.getConfiguredClientId()).isNull();
        assertThat(service.buildCacheKey()).isEqualTo("consumer:default");
    }

    @Test
    @DisplayName("buildCacheKey combines the key prefix with the configured consumer id")
    void buildCacheKey_usesConfiguredId() {
        ConsumerConfigService service = newService();

        assertThat(service.buildCacheKey()).isEqualTo("consumer:consumer-1");
    }

    @Test
    @DisplayName("fetchConfiguration delegates to the data handler with the configured consumer id")
    void fetchConfiguration_delegatesToDataHandler() {
        ConsumerConfigDTO dto = ConsumerConfigDTO.builder().clientId("consumer-1").build();
        when(dataHandler.getConsumerData("consumer-1")).thenReturn(dto);

        ConsumerConfigService service = newService();

        assertThat(service.fetchConfiguration()).isSameAs(dto);
    }

    // ------------------------------------------------------------------
    // ConfigService default methods (cache hit / miss / refresh / clear)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getConfiguration returns the cached value without calling the data handler (cache hit)")
    void getConfiguration_cacheHit_doesNotCallDataHandler() {
        ConsumerConfigDTO cached = ConsumerConfigDTO.builder().clientId("consumer-1").build();
        when(configStore.get("consumer:consumer-1", ConsumerConfigDTO.class)).thenReturn(Optional.of(cached));

        ConsumerConfigService service = newService();
        ConsumerConfigDTO result = service.getConsumerConfiguration();

        assertThat(result).isSameAs(cached);
        verify(dataHandler, never()).getConsumerData(any());
    }

    @Test
    @DisplayName("getConfiguration fetches and caches the value on a cache miss")
    void getConfiguration_cacheMiss_fetchesAndStores() {
        ConsumerConfigDTO fetched = ConsumerConfigDTO.builder().clientId("consumer-1").build();
        when(configStore.get("consumer:consumer-1", ConsumerConfigDTO.class)).thenReturn(Optional.empty());
        when(dataHandler.getConsumerData("consumer-1")).thenReturn(fetched);

        ConsumerConfigService service = newService();
        ConsumerConfigDTO result = service.getConsumerConfiguration();

        assertThat(result).isSameAs(fetched);
        verify(configStore).store("consumer:consumer-1", fetched);
    }

    @Test
    @DisplayName("getConfiguration wraps a data handler failure in ConfigFetchException")
    void getConfiguration_dataHandlerFails_throwsConfigFetchException() {
        when(configStore.get("consumer:consumer-1", ConsumerConfigDTO.class)).thenReturn(Optional.empty());
        when(dataHandler.getConsumerData("consumer-1")).thenThrow(new ManagementNodeDataException("boom"));

        ConsumerConfigService service = newService();

        assertThatThrownBy(service::getConsumerConfiguration)
                .isInstanceOf(ConfigFetchException.class)
                .hasCauseInstanceOf(ManagementNodeDataException.class);
    }

    @Test
    @DisplayName("refreshConfigurations clears the cache then fetches and stores fresh data")
    void refreshConfigurations_clearsThenFetchesAndStores() {
        ConsumerConfigDTO fetched = ConsumerConfigDTO.builder().clientId("consumer-1").build();
        when(dataHandler.getConsumerData("consumer-1")).thenReturn(fetched);

        ConsumerConfigService service = newService();
        service.refreshConfigurations();

        verify(configStore).clearCache();
        verify(configStore).store("consumer:consumer-1", fetched);
    }

    @Test
    @DisplayName("clearCache delegates to the config store")
    void clearCache_delegatesToConfigStore() {
        ConsumerConfigService service = newService();

        service.clearCache();

        verify(configStore).clearCache();
    }
}
