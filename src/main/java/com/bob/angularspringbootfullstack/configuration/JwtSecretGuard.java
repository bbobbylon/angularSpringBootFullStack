package com.bob.angularspringbootfullstack.configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Production-only startup guard that refuses to boot with a weak or placeholder JWT secret.
 * <p>
 * The signing secret is the root of trust for every access and refresh token minted by
 * {@link com.bob.angularspringbootfullstack.tokenprovider.TokenProvider} (HMAC512). If a real
 * deployment ever started with the dev fallback ({@code application-dev.yml}) or the
 * {@code .env.example} placeholder still in place, anyone could forge valid tokens for any user —
 * a total authentication bypass. This bean closes that hole by validating {@code jwt.secret}
 * during context startup.
 * <p>
 * It is annotated {@link Profile @Profile("prod")}, so Spring only instantiates it when the prod
 * profile is active; the dev profile is intentionally allowed to run with its convenient fallback
 * secret. Because the check lives in {@link PostConstruct}, throwing here aborts the
 * {@code ApplicationContext} refresh and the JVM exits before the web server binds a port — a true
 * fail-fast rather than a log-and-continue warning that someone might miss.
 *
 * @see com.bob.angularspringbootfullstack.tokenprovider.TokenProvider TokenProvider — signs/verifies with this secret
 */
@Slf4j
@Component
@Profile("prod")
public class JwtSecretGuard {

    /**
     * Minimum acceptable secret length in characters. HMAC512 derives a 512-bit key, so a longer
     * secret is preferable (64+ chars), but 32 is the floor below which the secret is too weak to
     * resist offline brute-forcing. Matches the "at least 32 chars" guidance in {@code .env.example}.
     */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * Known non-production secrets that must never reach a prod deployment: the dev profile's
     * fallback (see {@code application-dev.yml}) and the {@code .env.example} placeholder. Comparing
     * against the literals keeps the check explicit and self-documenting.
     */
    private static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "devOnlySecretKeyDoNotUseInProductionMustBeLongEnough1",
            "replace-with-random-base64-string-at-least-32-chars"
    );

    /** The configured signing secret, bound from {@code jwt.secret} (env {@code JWT_SECRET}). */
    @Value("${jwt.secret:}")
    private String secret;

    /**
     * Validates the JWT secret once the bean is constructed, before the application finishes
     * starting. Rejects a missing/blank secret, any known dev/placeholder value, and any secret
     * shorter than {@link #MIN_SECRET_LENGTH}.
     *
     * @throws IllegalStateException if the secret is unsafe for production; this aborts startup
     */
    @PostConstruct
    public void verifySecretStrength() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. The prod profile requires a strong, randomly generated "
                            + "secret (e.g. `openssl rand -base64 48`). Refusing to start.");
        }
        if (FORBIDDEN_SECRETS.contains(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the dev/placeholder value. Set a unique, randomly generated "
                            + "secret before deploying to production. Refusing to start.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short (" + secret.length() + " chars); it must be at least "
                            + MIN_SECRET_LENGTH + " characters for HMAC512 signing. Refusing to start.");
        }
        log.info("JWT secret strength check passed (length {} chars).", secret.length());
    }
}
