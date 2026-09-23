// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.util.Optional;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link InMemoryConfigurationStore}. Previously entirely untested (0% coverage).
 * <p>
 * The store's TTL is read once, at construction time, via {@link PropertyUtil}, a static
 * singleton this test never initialises for real. Each test therefore mocks
 * {@link PropertyUtil#getPropertyLongValue(String, String)} statically (Mockito 5's inline
 * mocking, already transitively available via {@code spring-boot-starter-test}) to control the
 * TTL used when {@link InMemoryConfigurationStore#getInstance()} lazily builds the singleton, and
 * resets the singleton via {@link InMemoryConfigurationStore#clearForTests()} before/after every
 * test so each test gets a fresh instance built with its own desired TTL.
 * </p>
 */
class InMemoryConfigurationStoreTest {

    private static final String TTL_PROPERTY = "management.node.cache.ttl.seconds";

    @BeforeEach
    void setUp() {
        InMemoryConfigurationStore.clearForTests();
    }

    @AfterEach
    void tearDown() {
        InMemoryConfigurationStore.clearForTests();
    }

    /** Builds a fresh singleton instance configured with the given TTL (in seconds). */
    private InMemoryConfigurationStore newStoreWithTtlSeconds(long ttlSeconds) {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertyLongValue(TTL_PROPERTY, "3600"))
                    .thenReturn(ttlSeconds);
            return InMemoryConfigurationStore.getInstance();
        }
    }

    @Test
    @DisplayName("getInstance returns the same singleton instance across calls")
    void getInstance_returnsSameSingleton() {
        InMemoryConfigurationStore first = newStoreWithTtlSeconds(3600);
        InMemoryConfigurationStore second = InMemoryConfigurationStore.getInstance();

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("clearForTests forces a new singleton to be built on the next getInstance() call")
    void clearForTests_forcesNewSingleton() {
        InMemoryConfigurationStore first = newStoreWithTtlSeconds(3600);

        InMemoryConfigurationStore.clearForTests();

        InMemoryConfigurationStore second = newStoreWithTtlSeconds(3600);
        assertThat(second).isNotSameAs(first);
    }

    @Test
    @DisplayName("store then get returns the stored value (cache hit)")
    void storeThenGet_returnsStoredValue() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        store.store("key1", "value1");
        Optional<String> result = store.get("key1", String.class);

        assertThat(result).contains("value1");
    }

    @Test
    @DisplayName("get for a key never stored returns empty (cache miss)")
    void get_missingKey_returnsEmpty() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        Optional<String> result = store.get("missing", String.class);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("storing twice under the same key overwrites the previous value")
    void store_overwritesPreviousValue() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        store.store("key1", "value1");
        store.store("key1", "value2");
        Optional<String> result = store.get("key1", String.class);

        assertThat(result).contains("value2");
        assertThat(store.getCacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("get on an entry past its TTL returns empty and evicts the entry (expiry)")
    void get_expiredEntry_returnsEmptyAndEvicts() {
        // Negative TTL guarantees expiresAt is already in the past the instant the entry is
        // stored, avoiding any timing flakiness from a real (even very short) positive TTL.
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(-5);

        store.store("key1", "value1");
        Optional<String> result = store.get("key1", String.class);

        assertThat(result).isEmpty();
        assertThat(store.getCacheSize()).isZero();
    }

    @Test
    @DisplayName("get with a mismatched requested type returns empty and evicts the entry")
    void get_typeMismatch_returnsEmptyAndEvicts() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);
        store.store("key1", "value1"); // stored as a String

        Optional<Integer> result = store.get("key1", Integer.class);

        assertThat(result).isEmpty();
        assertThat(store.getCacheSize()).isZero();
    }

    @Test
    @DisplayName("clearCache removes all entries")
    void clearCache_removesAllEntries() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);
        store.store("key1", "value1");
        store.store("key2", "value2");

        store.clearCache();

        assertThat(store.getCacheSize()).isZero();
        assertThat(store.get("key1", String.class)).isEmpty();
    }

    @Test
    @DisplayName("getCacheSize reflects the number of stored entries")
    void getCacheSize_reflectsEntryCount() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);
        assertThat(store.getCacheSize()).isZero();

        store.store("key1", "value1");
        assertThat(store.getCacheSize()).isEqualTo(1);

        store.store("key2", "value2");
        assertThat(store.getCacheSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("store throws NullPointerException when the key is null")
    void store_nullKey_throwsNullPointerException() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        assertThatThrownBy(() -> store.store(null, "value")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("store throws NullPointerException when the config value is null")
    void store_nullConfig_throwsNullPointerException() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        assertThatThrownBy(() -> store.store("key1", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("get throws NullPointerException when the key is null")
    void get_nullKey_throwsNullPointerException() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        assertThatThrownBy(() -> store.get(null, String.class)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("get throws NullPointerException when the type is null")
    void get_nullType_throwsNullPointerException() {
        InMemoryConfigurationStore store = newStoreWithTtlSeconds(3600);

        assertThatThrownBy(() -> store.get("key1", null)).isInstanceOf(NullPointerException.class);
    }
}
