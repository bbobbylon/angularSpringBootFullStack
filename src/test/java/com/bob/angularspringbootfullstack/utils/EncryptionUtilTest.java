package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link EncryptionUtil}'s AES-256-GCM round trip and, more importantly, that tampering
 * with a stored ciphertext is actually detected rather than silently producing corrupted
 * plaintext — the property that makes GCM a real safeguard for the SSO client secrets it protects
 * (FUTURE-ENHANCEMENTS.md §3.1), not just an obfuscation step.
 */
class EncryptionUtilTest {

    private EncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() {
        encryptionUtil = new EncryptionUtil();
        // A fixed, valid 32-byte key — independent of application-dev.yml's own fallback so this
        // test does not silently start passing/failing if that value ever changes.
        ReflectionTestUtils.setField(encryptionUtil, "base64Key",
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
    }

    @Test
    @DisplayName("decrypt(encrypt(x)) returns the original plaintext")
    void roundTripsPlaintext() {
        String plaintext = "super-secret-oidc-client-secret";
        String encrypted = encryptionUtil.encrypt(plaintext);
        assertEquals(plaintext, encryptionUtil.decrypt(encrypted));
    }

    @Test
    @DisplayName("two encryptions of the same plaintext produce different ciphertext (random IV)")
    void encryptionIsNotDeterministic() {
        String plaintext = "same-secret-both-times";
        assertNotEquals(encryptionUtil.encrypt(plaintext), encryptionUtil.encrypt(plaintext));
    }

    @Test
    @DisplayName("a tampered ciphertext fails GCM authentication instead of decrypting to garbage")
    void tamperedCiphertextFailsToDecrypt() {
        String encrypted = encryptionUtil.encrypt("super-secret-oidc-client-secret");
        byte[] blob = Base64.getDecoder().decode(encrypted);
        // Flip a bit well past the 12-byte IV, inside the ciphertext/tag region.
        blob[blob.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(blob);

        assertThrows(ApiException.class, () -> encryptionUtil.decrypt(tampered));
    }

    @Test
    @DisplayName("missing key fails fast rather than encrypting with a null key")
    void missingKeyFailsFast() {
        ReflectionTestUtils.setField(encryptionUtil, "base64Key", "");
        assertThrows(IllegalStateException.class, () -> encryptionUtil.encrypt("anything"));
    }

    @Test
    @DisplayName("wrong-length key fails fast rather than silently truncating/padding")
    void wrongLengthKeyFailsFast() {
        ReflectionTestUtils.setField(encryptionUtil, "base64Key",
                Base64.getEncoder().encodeToString("too-short".getBytes()));
        assertThrows(IllegalStateException.class, () -> encryptionUtil.encrypt("anything"));
    }
}
