package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for the {@code oauthclients} table (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B).
 * <p>
 * Named parameters throughout, matching every other {@code *Query} class in this codebase — bound
 * via {@link org.springframework.jdbc.core.namedparam.MapSqlParameterSource} in
 * {@code OAuthClientRepoImpl}. Mirrors {@code ApiKeyQuery} in shape; the one structural
 * difference is {@link #SELECT_OAUTH_CLIENT_BY_CLIENT_ID_QUERY} does not filter on {@code revoked}
 * the way {@code ApiKeyQuery#SELECT_ACTIVE_API_KEY_BY_HASH_QUERY} does — a bcrypt secret cannot be
 * looked up by equality the way a SHA-256 hash can, so the row must be fetched first and the
 * {@code revoked}/secret checks applied afterward in {@code OAuthClientServiceImpl#authenticate}.
 */
public class OAuthClientQuery {

    /**
     * Inserts a new client-credentials row. The generated id is recovered via a
     * {@code GeneratedKeyHolder}, the same convention every other {@code *RepoImpl#create} uses.
     * {@code client_id} carries a unique constraint so a collision between two independently
     * generated random ids fails loudly instead of aliasing two service accounts' credentials.
     * Parameters: userId, clientId, clientSecretHash, name, createdBy (nullable)
     */
    public static final String INSERT_OAUTH_CLIENT_QUERY =
            "INSERT INTO oauthclients (user_id, client_id, client_secret_hash, name, created_by) " +
            "VALUES (:userId, :clientId, :clientSecretHash, :name, :createdBy)";

    /**
     * Resolves a client_id to its row regardless of revoked state — see class Javadoc for why the
     * revoked check cannot be folded into this query the way it is for API keys.
     * Parameter: clientId
     */
    public static final String SELECT_OAUTH_CLIENT_BY_CLIENT_ID_QUERY =
            "SELECT * FROM oauthclients WHERE client_id = :clientId";

    /**
     * Resolves one client by id, regardless of its revoked state — used to verify a client belongs
     * to the service account named in the URL before acting on it, and to look up the owning row
     * before a revoke (see {@code OAuthClientRepoImpl#revoke}).
     * Parameter: id
     */
    public static final String SELECT_OAUTH_CLIENT_BY_ID_QUERY =
            "SELECT * FROM oauthclients WHERE id = :id";

    /**
     * Lists every client-credentials pair belonging to one service account (revoked included, so
     * the admin panel can show a client's full history), newest first.
     * Parameter: userId
     */
    public static final String SELECT_OAUTH_CLIENTS_BY_USER_ID_QUERY =
            "SELECT * FROM oauthclients WHERE user_id = :userId ORDER BY created_at DESC";

    /**
     * Marks a client revoked. Idempotent by design, but NOT by affected-row count — same
     * {@code useAffectedRows=true} caveat as {@code ApiKeyQuery#REVOKE_API_KEY_QUERY}, so
     * {@code OAuthClientRepoImpl#revoke} checks existence via
     * {@link #SELECT_OAUTH_CLIENT_BY_ID_QUERY} first rather than branching on rows-affected.
     * Parameter: id
     */
    public static final String REVOKE_OAUTH_CLIENT_QUERY =
            "UPDATE oauthclients SET revoked = TRUE WHERE id = :id";

    /**
     * Stamps {@code last_used_at = NOW()} on a successful token mint. Best-effort bookkeeping for
     * the admin panel ("last seen"), not a security control — a failed update here must never fail
     * the token issuance it rides on.
     * Parameter: id
     */
    public static final String TOUCH_OAUTH_CLIENT_LAST_USED_QUERY =
            "UPDATE oauthclients SET last_used_at = NOW() WHERE id = :id";
}
