// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.dsi.dpn.common.exception.AesCryptographicOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Additional unit tests for {@link AesCryptoUtil}, covering the edge cases
 * {@link AesCryptoUtilTest} does not: blank/invalid keys (both raise {@link IllegalArgumentException}
 * from {@code keyFromBase64}, shared by both encrypt and decrypt) and malformed/too-short ciphertext.
 */
class AesCryptoUtilAdditionalTest {

    private static String freshKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    // ------------------------------------------------------------------
    // keyFromBase64 - blank / invalid-length keys, via both encrypt() and decrypt()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("encrypt() throws IllegalArgumentException when the key is null")
    void encrypt_nullKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> AesCryptoUtil.encrypt("text", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key is blank");
    }

    @Test
    @DisplayName("encrypt() throws IllegalArgumentException when the key is blank")
    void encrypt_blankKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> AesCryptoUtil.encrypt("text", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key is blank");
    }

    @Test
    @DisplayName("encrypt() throws IllegalArgumentException when the decoded key length is not 16/24/32 bytes")
    void encrypt_invalidKeyLength_throwsIllegalArgumentException() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[10]);

        assertThatThrownBy(() -> AesCryptoUtil.encrypt("text", shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AES key must be 16, 24, or 32 bytes");
    }

    @Test
    @DisplayName("encrypt() succeeds with a 16-byte (AES-128) key")
    void encrypt_16ByteKey_succeeds() {
        String key = Base64.getEncoder().encodeToString(new byte[16]);

        String cipherText = AesCryptoUtil.encrypt("aes-128 text", key);

        assertThat(AesCryptoUtil.decrypt(cipherText, key)).isEqualTo("aes-128 text");
    }

    @Test
    @DisplayName("encrypt() succeeds with a 24-byte (AES-192) key")
    void encrypt_24ByteKey_succeeds() {
        String key = Base64.getEncoder().encodeToString(new byte[24]);

        String cipherText = AesCryptoUtil.encrypt("aes-192 text", key);

        assertThat(AesCryptoUtil.decrypt(cipherText, key)).isEqualTo("aes-192 text");
    }

    @Test
    @DisplayName("decrypt() throws IllegalArgumentException when the key is blank")
    void decrypt_blankKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> AesCryptoUtil.decrypt("anything", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key is blank");
    }

    // ------------------------------------------------------------------
    // decryptFromBase64 - malformed / too-short ciphertext
    // ------------------------------------------------------------------

    @Test
    @DisplayName("decrypt() throws AesCryptographicOperationException when the ciphertext is too short "
            + "to contain an IV")
    void decrypt_ciphertextTooShort_throwsAesCryptographicOperationException() {
        String key = freshKey();
        String tooShort = Base64.getEncoder().encodeToString(new byte[5]);

        assertThatThrownBy(() -> AesCryptoUtil.decrypt(tooShort, key))
                .isInstanceOf(AesCryptographicOperationException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("decrypt() throws AesCryptographicOperationException when the ciphertext is not valid Base64")
    void decrypt_invalidBase64_throwsAesCryptographicOperationException() {
        String key = freshKey();

        assertThatThrownBy(() -> AesCryptoUtil.decrypt("not valid base64!!! ###", key))
                .isInstanceOf(AesCryptographicOperationException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("decrypt() throws AesCryptographicOperationException when the ciphertext/tag has been corrupted "
            + "(GCM authentication failure)")
    void decrypt_corruptedCiphertext_throwsAesCryptographicOperationException() {
        String key = freshKey();
        String genuineCipherText = AesCryptoUtil.encrypt("some genuine plaintext", key);
        byte[] bytes = Base64.getDecoder().decode(genuineCipherText);
        // Flip a byte well past the 12-byte IV, inside the ciphertext/tag region.
        bytes[bytes.length - 1] ^= 0x01;
        String corrupted = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> AesCryptoUtil.decrypt(corrupted, key))
                .isInstanceOf(AesCryptographicOperationException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("encrypt() throws AesCryptographicOperationException when the plaintext is null")
    void encrypt_nullPlainText_throwsAesCryptographicOperationException() {
        String key = freshKey();

        assertThatThrownBy(() -> AesCryptoUtil.encrypt(null, key))
                .isInstanceOf(AesCryptographicOperationException.class)
                .hasMessageContaining("encryption failed");
    }
}
