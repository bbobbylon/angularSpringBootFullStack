package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants backing the favorites / pinned-destinations bar (FUTURE-ENHANCEMENTS.md §3.3,
 * POST-SUBMISSION-UPGRADES.md). {@code userfavorites} is a thin, per-user set of opaque
 * {@code destination_id} strings — ids into the frontend command-palette registry, never
 * re-validated against a route list here (see {@code schema.sql}'s comment on the table for why).
 * <p>
 * Named parameters throughout, for {@link org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate}.
 */
public class FavoriteQuery {

    /**
     * Selects every destination id the given user has pinned, oldest first — so a bar rendering
     * them in insertion order does not need a separate sort column.
     * Parameter: userId
     */
    public static final String SELECT_FAVORITES_BY_USER_ID_QUERY =
            "SELECT destination_id FROM userfavorites WHERE user_id = :userId ORDER BY created_at ASC";

    /**
     * Counts how many destinations the given user currently has pinned, so the service layer can
     * enforce the pin-count cap before inserting a new one.
     * Parameter: userId
     */
    public static final String COUNT_FAVORITES_BY_USER_ID_QUERY =
            "SELECT COUNT(*) FROM userfavorites WHERE user_id = :userId";

    /**
     * Pins a destination for a user. {@code INSERT IGNORE} makes this naturally idempotent: the
     * composite primary key {@code (user_id, destination_id)} means pinning an already-pinned
     * destination is a silent no-op rather than a duplicate-key error, so the service layer does
     * not need its own existence check before calling this.
     * Parameters: userId, destinationId
     */
    public static final String INSERT_FAVORITE_QUERY =
            "INSERT IGNORE INTO userfavorites (user_id, destination_id) VALUES (:userId, :destinationId)";

    /**
     * Unpins a destination for a user. Deleting a destination that was never pinned affects zero
     * rows rather than erroring — removal is idempotent by design (POST /user/favorites is a
     * personal preference toggle, not a data-integrity boundary).
     * Parameters: userId, destinationId
     */
    public static final String DELETE_FAVORITE_QUERY =
            "DELETE FROM userfavorites WHERE user_id = :userId AND destination_id = :destinationId";

    private FavoriteQuery() {
    }
}
