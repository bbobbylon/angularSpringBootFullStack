package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for the federated account-link ticket store (FUTURE-ENHANCEMENTS §2.4),
 * consumed by {@code ProviderLinkTicketService} through {@code NamedParameterJdbcTemplate},
 * following the same centralized-query convention as {@link SessionQuery}.
 *
 * <p>{@link #DELETE_TICKET_QUERY} does double duty: it is both the expiry/mismatch cleanup
 * statement and the atomic-consume primitive. A {@code DELETE ... WHERE ticket = :ticket}
 * affects at most one row no matter how many callers race to run it concurrently — across
 * threads on one instance or across separate instances behind a load balancer — so checking its
 * affected-row count is what gives {@code ProviderLinkTicketService#redeem} the same "exactly one
 * caller wins" guarantee the old {@code ConcurrentHashMap.remove(key, value)} provided in memory.
 */
public class ProviderLinkTicketQuery {

    /**
     * Mints a ticket. Parameters: ticket (the UUID, also the primary key), userId, provider,
     * expiresAt.
     */
    public static final String INSERT_TICKET_QUERY =
            "INSERT INTO providerlinktickets (ticket, user_id, provider, expires_at) " +
            "VALUES (:ticket, :userId, :provider, :expiresAt)";

    /**
     * Looks up a ticket without consuming it, so {@code redeem} can check provider/expiry before
     * deciding whether this caller is even entitled to consume the row. Parameter: ticket.
     */
    public static final String SELECT_TICKET_QUERY =
            "SELECT * FROM providerlinktickets WHERE ticket = :ticket";

    /**
     * The atomic consume — see class Javadoc. Also used standalone to drop an expired row found
     * by {@link #SELECT_TICKET_QUERY}. Parameter: ticket.
     */
    public static final String DELETE_TICKET_QUERY =
            "DELETE FROM providerlinktickets WHERE ticket = :ticket";

    /**
     * Opportunistic housekeeping run on every {@code mint} (mirroring the in-memory store's
     * purge-on-mint policy) so an abandoned ticket cannot accumulate indefinitely without a
     * dedicated cleanup job. No parameters.
     */
    public static final String DELETE_EXPIRED_TICKETS_QUERY =
            "DELETE FROM providerlinktickets WHERE expires_at <= NOW()";
}
