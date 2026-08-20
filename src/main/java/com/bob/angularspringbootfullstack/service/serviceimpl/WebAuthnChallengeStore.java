package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use WebAuthn ceremony challenges, structurally identical to
 * {@link ProviderLinkTicketService} — the same "opaque, single-use, expires in five minutes,
 * grants nothing on its own" shape, applied to a different pre-authentication round trip.
 *
 * <h3>Why the challenge itself is the lookup key</h3>
 * WebAuthn's correlation mechanism <em>is</em> the challenge: the browser signs whatever random
 * bytes the server handed it in the "options" call, and the "verify" call must find the exact
 * challenge it minted to check that signature against. There is no separate ticket id to invent —
 * the base64url encoding of the random challenge bytes doubles as the map key, exactly the way
 * {@code ProviderLinkTicketService}'s UUID ticket doubles as its own key.
 *
 * <h3>Why in-memory rather than a database table</h3>
 * Unlike {@code mfachallenges} (TOTP's login-challenge table), a WebAuthn challenge carries no
 * audit value once consumed — it is pure transient entropy, not evidence of "which second factor
 * was used." Modeling it the same way as the federation link ticket (rather than adding a table
 * that would only ever hold rows with a five-minute lifespan) keeps the same accepted tradeoff
 * this codebase already carries for rate-limit buckets and link tickets: the store is per-instance,
 * so a ceremony started on one node must finish on the same one. Both legs of a WebAuthn ceremony
 * happen within seconds of each other from the same browser, which is the same justification
 * {@code ProviderLinkTicketService} already documents. Behind a load balancer without sticky
 * sessions this needs to move to a shared store — the interface would not change.
 */
@Service
@Slf4j
public class WebAuthnChallengeStore {

    /** How long a minted challenge remains redeemable — long enough for an authenticator prompt. */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private final Map<String, PendingChallenge> challenges = new ConcurrentHashMap<>();

    /** What a challenge was minted for — registering a new credential, or authenticating with one. */
    public enum Purpose {
        REGISTER, AUTHENTICATE
    }

    /**
     * A pending ceremony.
     *
     * @param challenge the webauthn4j challenge object the ceremony was minted with
     * @param purpose   REGISTER or AUTHENTICATE — a challenge minted for one can never satisfy the other
     * @param userId    the authenticated caller for a REGISTER ceremony; null for AUTHENTICATE,
     *                  where the server does not know who is signing in until the assertion names
     *                  a credential id
     * @param expiresAt when the challenge stops being redeemable
     */
    private record PendingChallenge(Challenge challenge, Purpose purpose, Long userId, Instant expiresAt) {
    }

    /**
     * Mints a fresh random challenge for a registration ceremony, bound to the already-authenticated
     * caller.
     *
     * @param userId the signed-in account, taken from the JWT principal — never from a request body
     * @return the challenge to embed in the {@code PublicKeyCredentialCreationOptions} sent to the browser
     */
    public Challenge mintForRegistration(Long userId) {
        return mint(Purpose.REGISTER, userId);
    }

    /**
     * Mints a fresh random challenge for a usernameless authentication ceremony. No user is known
     * yet — that is the entire point of a discoverable/resident-key login — so the store cannot
     * bind this challenge to an account the way registration does.
     *
     * @return the challenge to embed in the {@code PublicKeyCredentialRequestOptions} sent to the browser
     */
    public Challenge mintForAuthentication() {
        return mint(Purpose.AUTHENTICATE, null);
    }

    private Challenge mint(Purpose purpose, Long userId) {
        purgeExpired();
        Challenge challenge = new DefaultChallenge();
        String key = encodeChallenge(challenge);
        challenges.put(key, new PendingChallenge(challenge, purpose, userId, Instant.now().plus(CHALLENGE_TTL)));
        log.debug("[WEBAUTHN] Minted {} challenge{}", purpose, userId == null ? "" : " for userId=" + userId);
        return challenge;
    }

    /**
     * The outcome of a successful redemption. A wrapper record (rather than returning
     * {@code Optional<Long>} directly) because {@code userId} is legitimately {@code null} for an
     * AUTHENTICATE redemption — the presence of this record, not the nullability of its field, is
     * what tells the caller redemption succeeded.
     *
     * @param challenge the original webauthn4j challenge object, for building {@code ServerProperty}
     * @param userId    the account bound at mint time (REGISTER only; always null for AUTHENTICATE)
     */
    public record RedeemedChallenge(Challenge challenge, Long userId) {
    }

    /**
     * Redeems a challenge presented back by the browser, consuming it. A challenge minted for one
     * purpose can never satisfy the other, so a stray REGISTER challenge cannot be replayed to
     * complete a login and vice versa.
     *
     * @param challengeBase64Url the {@code response.clientDataJSON}-derived challenge value the
     *                           client echoed back, base64url-encoded exactly as it was issued
     * @param purpose            the ceremony this redemption expects to be completing
     * @return the redemption outcome, or empty when the challenge is unknown, expired, consumed,
     *         or minted for the other purpose
     */
    public Optional<RedeemedChallenge> redeem(String challengeBase64Url, Purpose purpose) {
        if (challengeBase64Url == null || challengeBase64Url.isBlank()) return Optional.empty();

        PendingChallenge found = challenges.get(challengeBase64Url);
        if (found == null) {
            log.debug("[WEBAUTHN] Challenge not found or already used.");
            return Optional.empty();
        }
        if (Instant.now().isAfter(found.expiresAt())) {
            challenges.remove(challengeBase64Url, found);
            log.debug("[WEBAUTHN] Challenge expired.");
            return Optional.empty();
        }
        if (found.purpose() != purpose) {
            // Deliberately does NOT consume on a purpose mismatch — same reasoning as
            // ProviderLinkTicketService's provider mismatch: consuming here would let anyone who
            // learns a challenge value cancel somebody else's in-flight ceremony for free.
            log.warn("[WEBAUTHN] Challenge purpose mismatch: minted for '{}', redeemed as '{}'", found.purpose(), purpose);
            return Optional.empty();
        }
        // Atomic consume: remove(key, value) succeeds for exactly one caller, so two concurrent
        // redemptions of the same challenge cannot both be honored (replay protection).
        if (!challenges.remove(challengeBase64Url, found)) {
            log.debug("[WEBAUTHN] Challenge was consumed concurrently.");
            return Optional.empty();
        }
        return Optional.of(new RedeemedChallenge(found.challenge(), found.userId()));
    }

    /**
     * Base64url-encodes a webauthn4j challenge's raw bytes without padding — this is also the exact
     * form embedded in the browser's {@code clientDataJSON}, so {@code PasskeyServiceImpl} uses this
     * same method to turn a parsed response's echoed-back challenge into the lookup key for
     * {@link #redeem}.
     *
     * @param challenge the challenge to encode
     * @return the encoded string, also used as this store's map key
     */
    public static String encodeChallenge(Challenge challenge) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge.getValue());
    }

    /**
     * Drops expired entries so an abandoned ceremony cannot accumulate indefinitely. Called on
     * mint rather than on a schedule — same reasoning as {@link ProviderLinkTicketService}.
     */
    private void purgeExpired() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }
}
