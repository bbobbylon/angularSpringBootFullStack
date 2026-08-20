package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.webauthn4j.data.client.challenge.Challenge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.service.serviceimpl.WebAuthnChallengeStore.Purpose.AUTHENTICATE;
import static com.bob.angularspringbootfullstack.service.serviceimpl.WebAuthnChallengeStore.Purpose.REGISTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link WebAuthnChallengeStore} — no Spring context, no database. Structurally
 * mirrors what a {@code ProviderLinkTicketServiceTest} would cover, since this class is a direct
 * structural copy of that service applied to a different pre-authentication round trip.
 *
 * <p>Three properties earn a test: a challenge is single-use (replay after redemption fails), a
 * challenge minted for one purpose can never satisfy the other (a stray REGISTER challenge cannot
 * complete a login), and expiry is enforced without needing to sleep in the test — the internal map
 * is reached via reflection to backdate an entry's expiry instead.
 */
class WebAuthnChallengeStoreTest {

    @Test
    @DisplayName("a registration challenge redeems successfully once and carries the minting user id")
    void registrationChallengeRedeemsWithUserId() {
        WebAuthnChallengeStore store = new WebAuthnChallengeStore();
        Challenge challenge = store.mintForRegistration(42L);
        String key = WebAuthnChallengeStore.encodeChallenge(challenge);

        Optional<WebAuthnChallengeStore.RedeemedChallenge> redeemed = store.redeem(key, REGISTER);

        assertTrue(redeemed.isPresent());
        assertEquals(42L, redeemed.get().userId());
        assertEquals(challenge, redeemed.get().challenge());
    }

    @Test
    @DisplayName("an authentication challenge carries no user id — the server does not know who is signing in yet")
    void authenticationChallengeCarriesNoUserId() {
        WebAuthnChallengeStore store = new WebAuthnChallengeStore();
        Challenge challenge = store.mintForAuthentication();
        String key = WebAuthnChallengeStore.encodeChallenge(challenge);

        Optional<WebAuthnChallengeStore.RedeemedChallenge> redeemed = store.redeem(key, AUTHENTICATE);

        assertTrue(redeemed.isPresent(), "a successful AUTHENTICATE redemption must still be Optional.present");
        assertNull(redeemed.get().userId());
    }

    @Test
    @DisplayName("a challenge cannot be redeemed twice (replay protection)")
    void challengeIsSingleUse() {
        WebAuthnChallengeStore store = new WebAuthnChallengeStore();
        String key = WebAuthnChallengeStore.encodeChallenge(store.mintForRegistration(1L));

        assertTrue(store.redeem(key, REGISTER).isPresent(), "first redemption should succeed");
        assertFalse(store.redeem(key, REGISTER).isPresent(), "second redemption of the same challenge must fail");
    }

    @Test
    @DisplayName("a REGISTER challenge cannot be redeemed as AUTHENTICATE, or vice versa")
    void purposeMismatchIsRefused() {
        WebAuthnChallengeStore store = new WebAuthnChallengeStore();
        String registerKey = WebAuthnChallengeStore.encodeChallenge(store.mintForRegistration(1L));
        String authKey = WebAuthnChallengeStore.encodeChallenge(store.mintForAuthentication());

        assertFalse(store.redeem(registerKey, AUTHENTICATE).isPresent(),
                "a registration challenge must not complete a login");
        assertFalse(store.redeem(authKey, REGISTER).isPresent(),
                "a login challenge must not complete a registration");

        // And a purpose mismatch does NOT consume the challenge — it can still be redeemed correctly.
        assertTrue(store.redeem(registerKey, REGISTER).isPresent(),
                "a mismatched redemption attempt must not burn the challenge for its real purpose");
    }

    @Test
    @DisplayName("an unknown challenge value is refused")
    void unknownChallengeIsRefused() {
        WebAuthnChallengeStore store = new WebAuthnChallengeStore();

        assertFalse(store.redeem("not-a-real-challenge", REGISTER).isPresent());
        assertFalse(store.redeem(null, REGISTER).isPresent());
        assertFalse(store.redeem("", REGISTER).isPresent());
    }

    @Test
    @DisplayName("an expired challenge is refused even though it was never redeemed")
    @SuppressWarnings("unchecked")
    void expiredChallengeIsRefused() {
        WebAuthnChallengeStore store = new WebAuthnChallengeStore();
        Challenge challenge = store.mintForRegistration(1L);
        String key = WebAuthnChallengeStore.encodeChallenge(challenge);

        // Backdate the entry's expiry via reflection rather than sleeping the test 5 minutes.
        Map<String, Object> challenges = (Map<String, Object>) ReflectionTestUtils.getField(store, "challenges");
        Object pending = challenges.get(key);
        challenges.put(key, withPastExpiry(pending));

        assertFalse(store.redeem(key, REGISTER).isPresent());
    }

    /**
     * Rebuilds a {@code PendingChallenge} record with its expiry moved into the past, since records
     * are immutable and the store only exposes mint/redeem. Reflection is used only to reach the
     * private record type's constructor and accessors — a targeted exception to this suite's
     * otherwise black-box approach, justified by how expensive it would be to actually wait out a
     * 5-minute TTL in a test.
     */
    private static Object withPastExpiry(Object pendingChallenge) {
        try {
            Class<?> type = pendingChallenge.getClass();
            Object challenge = type.getMethod("challenge").invoke(pendingChallenge);
            Object purpose = type.getMethod("purpose").invoke(pendingChallenge);
            Object userId = type.getMethod("userId").invoke(pendingChallenge);
            var constructor = type.getDeclaredConstructor(
                    Challenge.class, WebAuthnChallengeStore.Purpose.class, Long.class, Instant.class);
            constructor.setAccessible(true);
            return constructor.newInstance(challenge, purpose, userId, Instant.now().minusSeconds(60));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
