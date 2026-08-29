package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for the WebAuthn ceremony-challenge store (FUTURE-ENHANCEMENTS §2.4), consumed
 * by {@code WebAuthnChallengeStore} through {@code NamedParameterJdbcTemplate}, following the
 * same centralized-query convention as {@link SessionQuery}.
 *
 * <p>Structurally identical to {@link ProviderLinkTicketQuery} — see that class's Javadoc for why
 * {@link #DELETE_CHALLENGE_QUERY}'s affected-row count is what makes redemption atomic across
 * instances, not just across threads on one JVM.
 */
public class WebAuthnChallengeQuery {

    /**
     * Mints a challenge. Parameters: challenge (the base64url-encoded value, also the primary
     * key), purpose, userId (null for an AUTHENTICATE challenge), expiresAt.
     */
    public static final String INSERT_CHALLENGE_QUERY =
            "INSERT INTO webauthnchallenges (challenge, purpose, user_id, expires_at) " +
            "VALUES (:challenge, :purpose, :userId, :expiresAt)";

    /**
     * Looks up a challenge without consuming it, so {@code redeem} can check purpose/expiry
     * before deciding whether this caller is entitled to consume the row. Parameter: challenge.
     */
    public static final String SELECT_CHALLENGE_QUERY =
            "SELECT * FROM webauthnchallenges WHERE challenge = :challenge";

    /**
     * The atomic consume — see class Javadoc. Also used standalone to drop an expired row found
     * by {@link #SELECT_CHALLENGE_QUERY}. Parameter: challenge.
     */
    public static final String DELETE_CHALLENGE_QUERY =
            "DELETE FROM webauthnchallenges WHERE challenge = :challenge";

    /**
     * Opportunistic housekeeping run on every {@code mint} (mirroring the in-memory store's
     * purge-on-mint policy) so an abandoned ceremony cannot accumulate indefinitely without a
     * dedicated cleanup job. No parameters.
     */
    public static final String DELETE_EXPIRED_CHALLENGES_QUERY =
            "DELETE FROM webauthnchallenges WHERE expires_at <= NOW()";
}
