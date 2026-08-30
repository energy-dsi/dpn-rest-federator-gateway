// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AesCryptoUtilTest {

    /** A valid Base64-encoded 256-bit AES key. */
    private static String freshKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test @DisplayName("encrypt then decrypt round-trips the plaintext")
    void roundTrip() {
        String key = freshKey();
        String plain = "hello dpn — federator token";
        String cipher = AesCryptoUtil.encrypt(plain, key);
        assertThat(cipher).isNotBlank().isNotEqualTo(plain);
        assertThat(AesCryptoUtil.decrypt(cipher, key)).isEqualTo(plain);
    }

    @Test @DisplayName("encryption is non-deterministic (random IV per call)")
    void randomIv() {
        String key = freshKey();
        assertThat(AesCryptoUtil.encrypt("same", key))
                .isNotEqualTo(AesCryptoUtil.encrypt("same", key));
    }

    @Test @DisplayName("decrypting with the wrong key fails")
    void wrongKeyFails() {
        String cipher = AesCryptoUtil.encrypt("secret", freshKey());
        assertThatThrownBy(() -> AesCryptoUtil.decrypt(cipher, freshKey()))
                .isInstanceOf(Exception.class);
    }
}
