package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for the {@code apikeys} table (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * <p>
 * Named parameters throughout, matching every other {@code *Query} class in this codebase — bound
 * via {@link org.springframework.jdbc.core.namedparam.MapSqlParameterSource} in
 * {@code ApiKeyRepoImpl}.
 */
public class ApiKeyQuery {

    /**
     * Inserts a new key row. The generated id is recovered via a {@code GeneratedKeyHolder}, the
     * same convention every other {@code *RepoImpl#create} uses. {@code key_hash} carries a unique
     * constraint (two keys can never hash to the same value in practice, but the constraint is
     * there so a bug that ever generated a duplicate raw key fails loudly instead of silently
     * aliasing two service accounts' credentials).
     * Parameters: userId, name, keyPrefix, keyHash, createdBy (nullable), expiresAt (nullable)
     */
    public static final String INSERT_API_KEY_QUERY =
            "INSERT INTO apikeys (user_id, name, key_prefix, key_hash, created_by, expires_at) " +
            "VALUES (:userId, :name, :keyPrefix, :keyHash, :createdBy, :expiresAt)";

    /**
     * Resolves a raw key's hash to its owning row, but only when the key is still usable: not
     * revoked and either unexpired or never set to expire. {@code ApiKeyAuthFilter} treats a miss
     * here identically to "no such key" — it does not distinguish revoked/expired/unknown, so this
     * query folds all three into one round trip rather than three.
     * Parameter: keyHash
     */
    public static final String SELECT_ACTIVE_API_KEY_BY_HASH_QUERY =
            "SELECT * FROM apikeys WHERE key_hash = :keyHash AND revoked = FALSE " +
            "AND (expires_at IS NULL OR expires_at > NOW())";

    /**
     * Resolves one key by id, regardless of its revoked/expired state — used to verify a key
     * belongs to the service account named in the URL before acting on it, and to look up the
     * owning row before a revoke (see {@code ApiKeyRepoImpl#revoke}).
     * Parameter: id
     */
    public static final String SELECT_API_KEY_BY_ID_QUERY =
            "SELECT * FROM apikeys WHERE id = :id";

    /**
     * Lists every key belonging to one service account (revoked and expired included, so the
     * admin panel can show a key's full history rather than only what is currently usable),
     * newest first.
     * Parameter: userId
     */
    public static final String SELECT_API_KEYS_BY_USER_ID_QUERY =
            "SELECT * FROM apikeys WHERE user_id = :userId ORDER BY created_at DESC";

    /**
     * Marks a key revoked. Idempotent by design, but NOT by affected-row count: MySQL Connector/J's
     * default {@code useAffectedRows=true} reports 0 rows changed both when the id does not exist
     * AND when the row already had {@code revoked = TRUE} — the two cases are indistinguishable from
     * this query's return value alone, which is why {@code ApiKeyRepoImpl#revoke} checks existence
     * via {@link #SELECT_API_KEY_BY_ID_QUERY} first rather than branching on rows-affected.
     * Parameter: id
     */
    public static final String REVOKE_API_KEY_QUERY =
            "UPDATE apikeys SET revoked = TRUE WHERE id = :id";

    /**
     * Stamps {@code last_used_at = NOW()} on a successful resolution. Best-effort bookkeeping for
     * the admin panel ("last seen"), not a security control — a failed update here would not be
     * worth failing the request over, so {@code ApiKeyServiceImpl#resolve} does not roll back
     * authentication if this write fails.
     * Parameter: id
     */
    public static final String TOUCH_API_KEY_LAST_USED_QUERY =
            "UPDATE apikeys SET last_used_at = NOW() WHERE id = :id";
}
