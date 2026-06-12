package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for authenticator-app MFA (SRS §4.5 FR-MFA-4): TOTP credentials,
 * single-use recovery codes, and login-time MFA challenges. Consumed by
 * {@code TotpServiceImpl} through {@code NamedParameterJdbcTemplate}, following the
 * same centralized-query convention as {@link UserQuery}.
 *
 * <p>Three tables, three lifecycles (all created in Flyway V5):
 * <ul>
 *   <li>{@code totpcredentials} — one secret per user; born unconfirmed at enrollment
 *       start, confirmed once the user proves possession of the authenticator.</li>
 *   <li>{@code totprecoverycodes} — SHA-256 hashes of single-use fallback codes;
 *       consumption is recorded in {@code used_at}, never deleted, so the audit story
 *       stays intact.</li>
 *   <li>{@code mfachallenges} — short-lived proof that the password (or federated)
 *       first factor succeeded; the verify endpoint refuses codes without one.</li>
 * </ul>
 */
public class TotpQuery {

    /**
     * Removes any prior enrollment attempt so a user restarting the wizard gets exactly
     * one pending secret (mirrors the delete-then-insert single-validity pattern used
     * by the SMS 2FA codes). Parameter: userId.
     */
    public static final String DELETE_TOTP_CREDENTIAL_BY_USER_ID_QUERY =
            "DELETE FROM totpcredentials WHERE user_id = :userId";

    /**
     * Inserts a fresh, UNCONFIRMED secret at enrollment start. Parameters: userId, secret.
     */
    public static final String INSERT_TOTP_CREDENTIAL_QUERY =
            "INSERT INTO totpcredentials (user_id, secret) VALUES (:userId, :secret)";

    /**
     * Selects the user's secret together with its confirmed flag; the service decides
     * whether a pending or confirmed secret is acceptable for the operation at hand.
     * Parameter: userId.
     */
    public static final String SELECT_TOTP_CREDENTIAL_BY_USER_ID_QUERY =
            "SELECT secret, confirmed FROM totpcredentials WHERE user_id = :userId";

    /**
     * Promotes the pending secret to confirmed once the user has echoed a valid code
     * back — the moment TOTP becomes the account's active second factor. Parameter: userId.
     */
    public static final String CONFIRM_TOTP_CREDENTIAL_QUERY =
            "UPDATE totpcredentials SET confirmed = TRUE, confirmed_at = NOW() WHERE user_id = :userId";

    /**
     * Mirrors {@code totpcredentials} state onto the denormalized {@code users.using_totp}
     * flag so row mappers and DTOs expose TOTP status without a join.
     * Parameters: usingTotp, userId.
     */
    public static final String UPDATE_USER_USING_TOTP_QUERY =
            "UPDATE users SET using_totp = :usingTotp WHERE id = :userId";

    /**
     * Clears all recovery codes (used and unused) — run before issuing a fresh batch at
     * enrollment and when TOTP is disabled. Parameter: userId.
     */
    public static final String DELETE_RECOVERY_CODES_BY_USER_ID_QUERY =
            "DELETE FROM totprecoverycodes WHERE user_id = :userId";

    /**
     * Stores one recovery code as its SHA-256 hex digest (plaintext is shown to the user
     * exactly once and never persisted). Parameters: userId, codeHash.
     */
    public static final String INSERT_RECOVERY_CODE_QUERY =
            "INSERT INTO totprecoverycodes (user_id, code_hash) VALUES (:userId, :codeHash)";

    /**
     * Consumes one matching, still-unused recovery code. The affected-row count is the
     * verification result: 1 = valid code consumed, 0 = unknown or already used — a
     * single statement that can never double-spend a code. Parameters: userId, codeHash.
     */
    public static final String CONSUME_RECOVERY_CODE_QUERY =
            "UPDATE totprecoverycodes SET used_at = NOW() " +
            "WHERE user_id = :userId AND code_hash = :codeHash AND used_at IS NULL";

    /**
     * Counts the unused recovery codes so the Account Security Center can warn when a
     * user is running low. Parameter: userId.
     */
    public static final String COUNT_UNUSED_RECOVERY_CODES_QUERY =
            "SELECT COUNT(*) FROM totprecoverycodes WHERE user_id = :userId AND used_at IS NULL";

    /**
     * Replaces any prior login challenge for this user (single active challenge, same
     * pattern as SMS codes). Parameter: userId.
     */
    public static final String DELETE_MFA_CHALLENGE_BY_USER_ID_QUERY =
            "DELETE FROM mfachallenges WHERE user_id = :userId";

    /**
     * Records that the first factor succeeded just now: the verify endpoint will only
     * honor codes presented with this challenge before it expires.
     * Parameters: userId, challenge, expirationDate.
     */
    public static final String INSERT_MFA_CHALLENGE_QUERY =
            "INSERT INTO mfachallenges (user_id, challenge, expiration_date) VALUES (:userId, :challenge, :expirationDate)";

    /**
     * Resolves a presented challenge back to its user, refusing expired rows in SQL so
     * the service has exactly one liveness check. Parameter: challenge.
     */
    public static final String SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY =
            "SELECT user_id FROM mfachallenges WHERE challenge = :challenge AND expiration_date > NOW()";

    /**
     * Deletes the challenge on successful verification (single-use). Parameter: challenge.
     */
    public static final String DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY =
            "DELETE FROM mfachallenges WHERE challenge = :challenge";
}
