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
     * Fetches every audit entry for a given user with the newest user being first.
     *
     * <p>JOINs {@code userevents → events → users} so the result includes the
     * human-readable {@code type} and {@code description} from the reference
     * table rather than a raw foreign key.  A {@code LIMIT} clause is omitted
     * for now — pagination will be added in a later iteration.
     */
    public static final String SELECT_EVENTS_BY_USER_ID_QUERY = "SELECT " +
            "uev.id, uev.device, uev.ip_address, ev.type, ev.description, uev.created_at " +
            "FROM events ev JOIN userevents uev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id WHERE u.id = :id " +
            "ORDER BY uev.created_at DESC";

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
