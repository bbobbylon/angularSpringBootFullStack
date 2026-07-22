package com.bob.angularspringbootfullstack.query;

/**
 * SQL query constants for reading and writing to the {@code userevents} audit log.
 *
 * <p>{@code userevents} is the dynamic log table that records every user action.
 * {@code events} is the static reference table (seeded in {@code schema.sql})
 * that holds the human-readable label and description for each action category.
 * The SELECT query JOINs both tables so callers receive a fully-resolved
 * {@link com.bob.angularspringbootfullstack.model.UserEvent} without a second round-trip.
 */
public class EventQuery {
    /**
     * Fetches every audit entry for a given user, newest first.
     *
     * <p>JOINs {@code userevents → events → users} so the result includes the
     * human-readable {@code type} and {@code description} from the reference
     * table rather than a raw foreign key.
     */
    public static final String SELECT_EVENTS_BY_USER_ID_QUERY = "SELECT " +
            "uev.id, uev.device, uev.ip_address, ev.type, ev.description, uev.created_at " +
            "FROM events ev JOIN userevents uev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id WHERE u.id = :id " +
            "ORDER BY uev.created_at DESC";

    /**
     * Same join as {@link #SELECT_EVENTS_BY_USER_ID_QUERY} but with {@code LIMIT}
     * and {@code OFFSET} so only one page of results is returned.
     *
     * <p>{@code :size} is the page size; {@code :offset} is {@code page * size},
     * calculated by the caller so the query stays parameter-only.
     */
    public static final String SELECT_EVENTS_BY_USER_ID_PAGINATED_QUERY = "SELECT " +
            "uev.id, uev.device, uev.ip_address, ev.type, ev.description, uev.created_at " +
            "FROM events ev JOIN userevents uev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id WHERE u.id = :id " +
            "ORDER BY uev.created_at DESC LIMIT :size OFFSET :offset";

    /**
     * Returns the total number of audit entries for a given user.
     *
     * <p>Used alongside {@link #SELECT_EVENTS_BY_USER_ID_PAGINATED_QUERY} so the
     * frontend can calculate the total page count without fetching all rows.
     */
    public static final String COUNT_EVENTS_BY_USER_ID_QUERY = "SELECT COUNT(*) " +
            "FROM userevents uev JOIN users u ON u.id = uev.user_id WHERE u.id = :id";

    /**
     * Counts {@code LOGIN_ATTEMPT_FAILURE} events for a given email address that
     * occurred at or after {@code :since} (a UTC timestamp string in MySQL
     * DATETIME format, e.g. {@code 2026-06-12 10:30:00}).
     *
     * <p>Used by the brute-force rate-limit check in
     * {@link com.bob.angularspringbootfullstack.controller.UserController}: if this
     * count reaches the threshold within the sliding window the login is refused
     * without triggering a DB-level account lock (SRS FR-EXT-1 partial, M6).
     */
    public static final String COUNT_RECENT_FAILURES_BY_EMAIL_QUERY =
            "SELECT COUNT(*) FROM userevents uev " +
            "JOIN events ev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id " +
            "WHERE u.email = :email AND ev.type = 'LOGIN_ATTEMPT_FAILURE' AND uev.created_at >= :since";

    /**
     * Inserts a new audit entry for a user identified by their email address.
     *
     * <p>Uses correlated subqueries to resolve the user ID and event type ID at
     * insert time so callers never have to look up those foreign keys themselves.
     * The {@code :type} parameter must exactly match a {@code type} value in the
     * {@code events} reference table — a mismatch causes the subquery to return
     * null, which triggers a NOT NULL constraint violation on {@code event_id}.
     */
    public static final String INSERT_EVENT_BY_USER_ID_QUERY = "INSERT INTO userevents (user_id, event_id, device, ip_address) " +
            "VALUES ((SELECT id FROM users WHERE email = :email), (SELECT id FROM events WHERE type = :type), :device, :ipAddress)";
}
