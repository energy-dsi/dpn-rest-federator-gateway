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
import org.dsi.dpn.common.model.dto.ProducerConfigDTO;
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
 * Unit tests for {@link ProducerConfigService} (9% coverage), mirroring
 * {@code ConsumerConfigServiceTest} for the producer-specific implementation. Together the two
 * test classes exercise all of the {@link ConfigService} default cache/fetch logic through
 * concrete implementations.
 */
class ProducerConfigServiceTest {

    private static final String PRODUCER_ID_PROP = "federator.producer.id";

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private ManagementNodeDataHandler dataHandler;
    private InMemoryConfigurationStore configStore;

    @BeforeEach
    void setUp() {
        ResilienceSupport.clearForTests();

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
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(PRODUCER_ID_PROP, null)).thenReturn("producer-1");
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

    private ProducerConfigService newService() {
        return new ProducerConfigService(dataHandler, configStore);
    }

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects a null data handler")
    void constructor_nullDataHandler_throwsNullPointerException() {
        assertThatThrownBy(() -> new ProducerConfigService(null, configStore)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects a null config store")
    void constructor_nullConfigStore_throwsNullPointerException() {
        assertThatThrownBy(() -> new ProducerConfigService(dataHandler, null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------
    // Contract accessors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("contract accessors expose the producer-specific prefix, id, and DTO type")
    void contractAccessors_exposeProducerSpecifics() {
        ProducerConfigService service = newService();

        assertThat(service.getConfigStore()).isSameAs(configStore);
        assertThat(service.getKeyPrefix()).isEqualTo("producer:");
        assertThat(service.getConfiguredClientId()).isEqualTo("producer-1");
        assertThat(service.getDtoClass()).isEqualTo(ProducerConfigDTO.class);
    }

    @Test
    @DisplayName("getConfiguredClientId is null and buildCacheKey falls back to 'default' when unconfigured")
    void getConfiguredClientId_propertyAbsent_fallsBackToDefaultCacheKey() {
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(PRODUCER_ID_PROP, null)).thenReturn(null);

        ProducerConfigService service = newService();

        assertThat(service.getConfiguredClientId()).isNull();
        assertThat(service.buildCacheKey()).isEqualTo("producer:default");
    }

    @Test
    @DisplayName("buildCacheKey combines the key prefix with the configured producer id")
    void buildCacheKey_usesConfiguredId() {
        ProducerConfigService service = newService();

        assertThat(service.buildCacheKey()).isEqualTo("producer:producer-1");
    }

    @Test
    @DisplayName("fetchConfiguration delegates to the data handler with the configured producer id")
    void fetchConfiguration_delegatesToDataHandler() {
        ProducerConfigDTO dto = ProducerConfigDTO.builder().clientId("producer-1").build();
        when(dataHandler.getProducerData("producer-1")).thenReturn(dto);

        ProducerConfigService service = newService();

        assertThat(service.fetchConfiguration()).isSameAs(dto);
    }

    // ------------------------------------------------------------------
    // ConfigService default methods (cache hit / miss / refresh / clear)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getConfiguration returns the cached value without calling the data handler (cache hit)")
    void getConfiguration_cacheHit_doesNotCallDataHandler() {
        ProducerConfigDTO cached = ProducerConfigDTO.builder().clientId("producer-1").build();
        when(configStore.get("producer:producer-1", ProducerConfigDTO.class)).thenReturn(Optional.of(cached));

        ProducerConfigService service = newService();
        ProducerConfigDTO result = service.getProducerConfiguration();

        assertThat(result).isSameAs(cached);
        verify(dataHandler, never()).getProducerData(any());
    }

    @Test
    @DisplayName("getConfiguration fetches and caches the value on a cache miss")
    void getConfiguration_cacheMiss_fetchesAndStores() {
        ProducerConfigDTO fetched = ProducerConfigDTO.builder().clientId("producer-1").build();
        when(configStore.get("producer:producer-1", ProducerConfigDTO.class)).thenReturn(Optional.empty());
        when(dataHandler.getProducerData("producer-1")).thenReturn(fetched);

        ProducerConfigService service = newService();
        ProducerConfigDTO result = service.getProducerConfiguration();

        assertThat(result).isSameAs(fetched);
        verify(configStore).store("producer:producer-1", fetched);
    }

    @Test
    @DisplayName("getConfiguration wraps a data handler failure in ConfigFetchException")
    void getConfiguration_dataHandlerFails_throwsConfigFetchException() {
        when(configStore.get("producer:producer-1", ProducerConfigDTO.class)).thenReturn(Optional.empty());
        when(dataHandler.getProducerData("producer-1")).thenThrow(new ManagementNodeDataException("boom"));

        ProducerConfigService service = newService();

        assertThatThrownBy(service::getProducerConfiguration)
                .isInstanceOf(ConfigFetchException.class)
                .hasCauseInstanceOf(ManagementNodeDataException.class);
    }

    @Test
    @DisplayName("refreshConfigurations clears the cache then fetches and stores fresh data")
    void refreshConfigurations_clearsThenFetchesAndStores() {
        ProducerConfigDTO fetched = ProducerConfigDTO.builder().clientId("producer-1").build();
        when(dataHandler.getProducerData("producer-1")).thenReturn(fetched);

        ProducerConfigService service = newService();
        service.refreshConfigurations();

        verify(configStore).clearCache();
        verify(configStore).store("producer:producer-1", fetched);
    }

    @Test
    @DisplayName("clearCache delegates to the config store")
    void clearCache_delegatesToConfigStore() {
        ProducerConfigService service = newService();

        service.clearCache();

        verify(configStore).clearCache();
    }
}
