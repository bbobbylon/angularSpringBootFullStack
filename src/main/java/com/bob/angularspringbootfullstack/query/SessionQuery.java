package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for the server-side refresh-session store (Flyway V6, plan.md M5,
 * SRS FR-JWT-5), consumed by {@code SessionServiceImpl} through
 * {@code NamedParameterJdbcTemplate}, following the same centralized-query convention
 * as {@link UserQuery}.
 *
 * <p>Revocation statements deliberately fold authorization into the SQL where a user
 * acts on their own sessions ({@code AND user_id = :userId}): the affected-row count is
 * then both the success signal and the ownership check, so a forged family id from
 * another user's session updates zero rows instead of leaking anything.
 */
public class SessionQuery {

    /**
     * Opens a session: one new family with its first concrete token. last_used_at and
     * created_at default to NOW() in the schema.
     * Parameters: userId, family, jti, device, ipAddress, expiresAt.
     */
    public static final String INSERT_SESSION_QUERY =
            "INSERT INTO refreshsessions (user_id, family, jti, device, ip_address, expires_at) " +
            "VALUES (:userId, :family, :jti, :device, :ipAddress, :expiresAt)";

    /**
     * Resolves a presented refresh token's jti to its session row — the rotation
     * pivot: missing row = unknown/legacy token, superseded/revoked row = reuse.
     * Parameter: jti.
     */
    public static final String SELECT_SESSION_BY_JTI_QUERY =
            "SELECT * FROM refreshsessions WHERE jti = :jti";

    /**
     * Retires one concrete token after rotation, stamping last_used_at as the
     * session's "last seen". The row is kept (not deleted) so a later replay of this
     * jti is recognizable as reuse. Parameter: id.
     */
    public static final String SUPERSEDE_SESSION_QUERY =
            "UPDATE refreshsessions SET superseded = TRUE, last_used_at = NOW() WHERE id = :id";

    /**
     * Nukes an entire family — the reuse-detection response (FR-JWT-5): every token
     * ever issued in the family stops refreshing, forcing a fresh first-factor login.
     * No user_id guard: this runs server-initiated on theft evidence, not on user
     * request. Parameter: family.
     */
    public static final String REVOKE_FAMILY_QUERY =
            "UPDATE refreshsessions SET revoked = TRUE WHERE family = :family AND revoked = FALSE";

    /**
     * User-initiated single-session revoke from the Security Center. Ownership is the
     * {@code user_id} predicate — zero affected rows means "not yours or not found",
     * indistinguishably. Parameters: family, userId.
     */
    public static final String REVOKE_FAMILY_FOR_USER_QUERY =
            "UPDATE refreshsessions SET revoked = TRUE WHERE family = :family AND user_id = :userId AND revoked = FALSE";

    /**
     * "Log out everywhere else": revokes every family except the one the caller is
     * currently using. Parameters: userId, family (the caller's current one).
     */
    public static final String REVOKE_OTHER_SESSIONS_QUERY =
            "UPDATE refreshsessions SET revoked = TRUE WHERE user_id = :userId AND family != :family AND revoked = FALSE";

    /**
     * Revokes everything for a user — run on password change so the device list agrees
     * with the {@code passwordChangedAt} token invalidation that already kills the old
     * JWTs (FR-JWT-6). Parameter: userId.
     */
    public static final String REVOKE_ALL_SESSIONS_QUERY =
            "UPDATE refreshsessions SET revoked = TRUE WHERE user_id = :userId AND revoked = FALSE";

    /**
     * The Security Center's device list: one row per LIVE family (current token only —
     * superseded rows are rotation history, not separate sessions), newest activity
     * first. Parameter: userId.
     */
    public static final String SELECT_ACTIVE_SESSIONS_BY_USER_QUERY =
            "SELECT * FROM refreshsessions " +
            "WHERE user_id = :userId AND revoked = FALSE AND superseded = FALSE AND expires_at > NOW() " +
            "ORDER BY last_used_at DESC";
}
