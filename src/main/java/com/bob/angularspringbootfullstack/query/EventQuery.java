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
     * <p>Uses an {@code INSERT ... SELECT} so the user and event-type lookups are
     * filters rather than scalar subqueries. If either lookup misses (unknown
     * email or unknown event type) the SELECT produces zero rows and the INSERT
     * is a silent no-op — instead of throwing a NOT NULL constraint violation
     * the way the older {@code VALUES ((SELECT ...))} form did. This matters most
     * on failed-login flows, where the email coming in is by definition often
     * one that does not exist; we don't want auditing to crash the login response.
     *
     * <p>{@code CROSS JOIN} is used because the two filter conditions are
     * independent — we're picking exactly one user row and exactly one event
     * row, with no relationship between them. The {@code WHERE} clauses make
     * each side a one-row (or zero-row) lookup, so the cross-product is at
     * most one row.
     */
    public static final String INSERT_EVENT_BY_USER_ID_QUERY = "INSERT INTO userevents (user_id, event_id, device, ip_address) " +
            "SELECT u.id, ev.id, :device, :ipAddress " +
            "FROM users u CROSS JOIN events ev " +
            "WHERE u.email = :email AND ev.type = :type";
}
