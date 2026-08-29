package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts and decrypts small secrets (currently: per-organization SSO client secrets — see
 * {@code OrganizationIdentityProviderServiceImpl}) at rest using AES-256-GCM.
 * <p>
 * This is the first encryption-at-rest primitive in the codebase — every other stored secret so
 * far is either one-way hashed (passwords via BCrypt, recovery codes) or, in the case of the TOTP
 * secret, plaintext because it must be re-derivable to validate live codes. A federated IdP's OIDC
 * client secret is different: it must be recoverable in full so it can be replayed to the IdP's
 * token endpoint on every login, so hashing is not an option, yet it is exactly as sensitive as a
 * password — hence a real reversible cipher instead of either existing pattern.
 * <p>
 * The key comes from {@code org.idp.secret-encryption-key} ({@code ORG_IDP_SECRET_ENCRYPTION_KEY}),
 * a base64-encoded 32-byte (256-bit) key. {@link #encrypt} generates a fresh random 12-byte IV per
 * call (GCM requires a unique IV per encryption under the same key), runs AES/GCM/NoPadding, and
 * concatenates {@code IV || ciphertext+tag} before base64-encoding the result so the IV travels
 * alongside what it protects. {@link #decrypt} reverses this. GCM's authentication tag makes
 * tampering with the stored ciphertext fail decryption outright, rather than silently returning
 * corrupted plaintext.
 * <p>
 * Startup validation of the key itself (missing/placeholder/wrong length) is
 * {@link com.bob.angularspringbootfullstack.configuration.OrgIdpEncryptionKeyGuard}'s job, mirroring
 * how {@code JwtSecretGuard} guards {@code jwt.secret} — this class assumes the key it is given is
 * well-formed and fails loudly (not silently) if it is not.
 */
@Component
public class EncryptionUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${org.idp.secret-encryption-key:}")
    private String base64Key;

    /**
     * Encrypts {@code plaintext} and returns a base64-encoded {@code IV || ciphertext+tag} blob
     * safe to store directly in a database column.
     *
     * @param plaintext the secret to encrypt; must not be null
     * @return the base64-encoded encrypted blob
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret.", e);
        }
    }

    /**
     * Decrypts a blob previously produced by {@link #encrypt}.
     *
     * @param encoded the base64-encoded {@code IV || ciphertext+tag} blob
     * @return the original plaintext
     * @throws ApiException if the blob is malformed or fails GCM authentication (tampered or
     *                       encrypted under a different key) — a client-facing "config is broken"
     *                       failure rather than an unhandled crypto exception
     */
    public String decrypt(String encoded) {
        try {
            byte[] blob = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(blob, GCM_IV_LENGTH_BYTES, blob.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ApiException("Stored identity provider secret could not be decrypted.");
        }
    }

    private SecretKeySpec secretKey() {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "ORG_IDP_SECRET_ENCRYPTION_KEY is not set. Generate one with `openssl rand -base64 32`.");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "ORG_IDP_SECRET_ENCRYPTION_KEY must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes for AES-256; got " + key.length + ".");
        }
        return new SecretKeySpec(key, KEY_ALGORITHM);
    }
}
