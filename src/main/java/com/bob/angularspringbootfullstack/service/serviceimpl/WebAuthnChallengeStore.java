package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.query.WebAuthnChallengeQuery.*;

/**
 * Short-lived, single-use WebAuthn ceremony challenges, structurally identical to
 * {@link ProviderLinkTicketService} — the same "opaque, single-use, expires in five minutes,
 * grants nothing on its own" shape, applied to a different pre-authentication round trip.
 *
 * <h3>Why the challenge itself is the lookup key</h3>
 * WebAuthn's correlation mechanism <em>is</em> the challenge: the browser signs whatever random
 * bytes the server handed it in the "options" call, and the "verify" call must find the exact
 * challenge it minted to check that signature against. There is no separate ticket id to invent —
 * the base64url encoding of the random challenge bytes doubles as the lookup key, exactly the way
 * {@code ProviderLinkTicketService}'s UUID ticket doubles as its own key.
 *
 * <h3>Storage (FUTURE-ENHANCEMENTS §2.4 — closed)</h3>
 * Originally an in-memory {@code ConcurrentHashMap}; now backed by the {@code webauthnchallenges}
 * table via {@link com.bob.angularspringbootfullstack.query.WebAuthnChallengeQuery}, for the same
 * cross-instance reason as {@link ProviderLinkTicketService} — a ceremony started on one node no
 * longer has to finish on that same node behind a load balancer without sticky sessions. Only the
 * storage changed: the table holds the raw challenge bytes' base64url encoding as its primary key
 * (no separate id column, matching the class's own "the challenge is the key" reasoning above),
 * and {@link DefaultChallenge}'s {@code String} constructor — which decodes with webauthn4j's own
 * {@code Base64UrlUtil}, the same codec {@link #encodeChallenge} uses to encode — reconstructs the
 * {@link Challenge} object on redemption without this class hand-rolling the decode itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAuthnChallengeStore {

    /** How long a minted challenge remains redeemable — long enough for an authenticator prompt. */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /** What a challenge was minted for — registering a new credential, or authenticating with one. */
    public enum Purpose {
        REGISTER, AUTHENTICATE
    }

    /**
     * One {@code webauthnchallenges} row, minus the challenge itself (already known to the caller
     * that looked it up).
     *
     * @param purpose   REGISTER or AUTHENTICATE — a challenge minted for one can never satisfy the other
     * @param userId    the authenticated caller for a REGISTER ceremony; null for AUTHENTICATE,
     *                  where the server does not know who is signing in until the assertion names
     *                  a credential id
     * @param expiresAt when the challenge stops being redeemable
     */
    private record ChallengeRow(Purpose purpose, Long userId, LocalDateTime expiresAt) {
    }

    private static final RowMapper<ChallengeRow> CHALLENGE_ROW_MAPPER = (rs, rowNum) -> new ChallengeRow(
            Purpose.valueOf(rs.getString("purpose")),
            rs.getObject("user_id", Long.class),
            rs.getTimestamp("expires_at").toLocalDateTime());

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
        jdbcTemplate.update(INSERT_CHALLENGE_QUERY, new MapSqlParameterSource()
                .addValue("challenge", key)
                .addValue("purpose", purpose.name())
                .addValue("userId", userId)
                .addValue("expiresAt", LocalDateTime.now().plus(CHALLENGE_TTL)));
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

        List<ChallengeRow> rows = jdbcTemplate.query(SELECT_CHALLENGE_QUERY, Map.of("challenge", challengeBase64Url), CHALLENGE_ROW_MAPPER);
        if (rows.isEmpty()) {
            log.debug("[WEBAUTHN] Challenge not found or already used.");
            return Optional.empty();
        }
        ChallengeRow found = rows.getFirst();
        if (LocalDateTime.now().isAfter(found.expiresAt())) {
            jdbcTemplate.update(DELETE_CHALLENGE_QUERY, Map.of("challenge", challengeBase64Url));
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
        // Atomic consume: a DELETE keyed on the primary key affects exactly one row for exactly
        // one caller, so two concurrent redemptions of the same challenge — even from two
        // different app instances — cannot both be honored (replay protection).
        int deleted = jdbcTemplate.update(DELETE_CHALLENGE_QUERY, Map.of("challenge", challengeBase64Url));
        if (deleted == 0) {
            log.debug("[WEBAUTHN] Challenge was consumed concurrently.");
            return Optional.empty();
        }
        return Optional.of(new RedeemedChallenge(new DefaultChallenge(challengeBase64Url), found.userId()));
    }

    /**
     * Base64url-encodes a webauthn4j challenge's raw bytes without padding — this is also the exact
     * form embedded in the browser's {@code clientDataJSON}, so {@code PasskeyServiceImpl} uses this
     * same method to turn a parsed response's echoed-back challenge into the lookup key for
     * {@link #redeem}.
     *
     * @param challenge the challenge to encode
     * @return the encoded string, also used as this store's table primary key
     */
    public static String encodeChallenge(Challenge challenge) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge.getValue());
    }

    /**
     * Drops expired rows so an abandoned ceremony cannot accumulate indefinitely. Called on
     * mint rather than on a schedule — same reasoning as {@link ProviderLinkTicketService}.
     */
    private void purgeExpired() {
        jdbcTemplate.update(DELETE_EXPIRED_CHALLENGES_QUERY, Map.of());
    }
}
