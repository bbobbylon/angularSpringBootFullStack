package com.bob.angularspringbootfullstack.configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Set;

/**
 * Production-only startup guard that refuses to boot with a missing, placeholder, or wrong-length
 * AES-256-GCM encryption key for per-organization SSO client secrets.
 * <p>
 * {@code org.idp.secret-encryption-key} ({@code ORG_IDP_SECRET_ENCRYPTION_KEY}) is the root of
 * trust {@link com.bob.angularspringbootfullstack.utils.EncryptionUtil} uses to encrypt/decrypt
 * every organization's OIDC client secret at rest. If a real deployment ever started with the dev
 * fallback key ({@code application-dev.yml}) — or with no key set at all, which
 * {@code EncryptionUtil} would otherwise only discover lazily on the first encrypt/decrypt call —
 * anyone with database read access could decrypt every organization's IdP client secret using a key
 * published in this very repository's dev config. This bean closes that hole the same way
 * {@link JwtSecretGuard} closes it for {@code jwt.secret}: validate eagerly, at startup, and abort
 * rather than let a bad key reach runtime.
 * <p>
 * Annotated {@link Profile @Profile("prod")} so only real deployments pay this cost; the dev
 * profile is intentionally allowed to run with its convenience fallback key.
 *
 * @see com.bob.angularspringbootfullstack.utils.EncryptionUtil EncryptionUtil — encrypts/decrypts with this key
 */
@Slf4j
@Component
@Profile("prod")
public class OrgIdpEncryptionKeyGuard {

    /** AES-256 requires exactly 32 raw key bytes, decoded from the base64-encoded property. */
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32;

    /** The dev profile's fallback key ({@code application-dev.yml}) — never valid in production. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "mxaAybb4ZEsJcc7K9MbK/rENPp7s+NvcHkYRVpaJAXA="
    );

    /** The configured encryption key, bound from {@code org.idp.secret-encryption-key}. */
    @Value("${org.idp.secret-encryption-key:}")
    private String base64Key;

    /**
     * Validates the encryption key once the bean is constructed, before the application finishes
     * starting. Rejects a missing/blank key, the known dev fallback, malformed base64, and any
     * decoded key that is not exactly {@link #REQUIRED_KEY_LENGTH_BYTES} bytes.
     *
     * @throws IllegalStateException if the key is unsafe for production; this aborts startup
     */
    @PostConstruct
    public void verifyKeyStrength() {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "ORG_IDP_SECRET_ENCRYPTION_KEY is not set. The prod profile requires a random "
                            + "32-byte key (e.g. `openssl rand -base64 32`). Refusing to start.");
        }
        if (FORBIDDEN_KEYS.contains(base64Key)) {
            throw new IllegalStateException(
                    "ORG_IDP_SECRET_ENCRYPTION_KEY is still the dev fallback value. Set a unique, "
                            + "randomly generated key before deploying to production. Refusing to start.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "ORG_IDP_SECRET_ENCRYPTION_KEY is not valid base64. Refusing to start.", e);
        }
        if (key.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "ORG_IDP_SECRET_ENCRYPTION_KEY must decode to exactly " + REQUIRED_KEY_LENGTH_BYTES
                            + " bytes for AES-256; got " + key.length + ". Refusing to start.");
        }
        log.info("Organization IdP encryption key strength check passed.");
    }
}
