package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants backing the administrative security dashboard (SRS FR-TPF-2).
 *
 * <p><b>No new storage.</b> Like {@link LoginRiskQuery}, every figure on the dashboard is read
 * back out of tables that already exist — {@code userevents} for the audit trail, {@code users}
 * for account state and MFA enrolment, {@code refreshsessions} for live sessions. FR-TPF-1 writes
 * {@code SUSPICIOUS_LOGIN} rows as a side effect of detection; this class is what turns that
 * write-only trail into something an administrator can actually look at. Adding a summary table
 * would have introduced a second version of the truth that could drift from the audit log, and the
 * audit log is the one that must be believed.
 *
 * <h3>Organization scoping (FR-ORG-2)</h3>
 * Every query joins {@code users} even where it does not select a user column, purely so the same
 * scope predicate can be appended to all of them. {@link #SCOPE_PREDICATE} is spliced in by
 * {@code SecurityDashboardRepoImpl} when the caller is a {@code ROLE_ORGANIZATION_ADMIN}. The
 * splice is a compile-time constant, never anything derived from a request — the organization ids
 * themselves are bound as a named parameter, so the injected fragment carries no user input and
 * cannot become an injection vector. String assembly is used rather than one query with an
 * always-on {@code OR :unscoped IS TRUE} clause because that form defeats index selection on
 * {@code userorganizations} and quietly turns every dashboard query into a full scan.
 *
 * <h3>Why the counters are event-derived rather than incremented</h3>
 * "Failed logins in the last 7 days" is a question about history, so it is answered by querying
 * history. A counter column would need to be reset, would lose the ability to answer the same
 * question over a different window, and would be wrong forever if a single increment were missed.
 *
 * @see com.bob.angularspringbootfullstack.repo.repoimpl.SecurityDashboardRepoImpl
 */
public class SecurityDashboardQuery {

    /**
     * Restricts a dashboard query to users who actively belong to one of the caller's organizations.
     *
     * <p>Appended verbatim where the marker {@link #SCOPE_MARKER} appears. Requires the enclosing
     * query to expose {@code users} under the alias {@code u}, and the caller to bind
     * {@code organizationIds}.
     *
     * <p>{@code uo.active = TRUE} matters: a lapsed membership must stop granting visibility the
     * moment it is deactivated, otherwise removing someone from an organization would leave them
     * able to watch its security telemetry indefinitely.
     */
    public static final String SCOPE_PREDICATE =
            " AND u.id IN (SELECT uo.user_id FROM userorganizations uo " +
            "WHERE uo.organization_id IN (:organizationIds) AND uo.active = TRUE) ";

    /**
     * The token replaced by either {@link #SCOPE_PREDICATE} or the empty string. Written as a SQL
     * comment so that an unsubstituted query is still valid SQL — a bug in the substitution shows
     * up as unscoped data in a test rather than as a syntax error in production, which is the
     * safer failure to discover late only if tests cover it; {@code SecurityDashboardRepoImplTest}
     * asserts the substitution happens.
     */
    public static final String SCOPE_MARKER = "/*SCOPE*/";

    /**
     * Totals per security-relevant event type since a cut-off — the dashboard's headline counters.
     *
     * <p>Groups rather than issuing one {@code COUNT} per type: six round trips to answer one
     * question is six chances for the numbers to disagree with each other, since each would see a
     * slightly different instant of the table.
     */
    public static final String COUNT_SECURITY_EVENTS_SINCE_QUERY =
            "SELECT ev.type AS event_type, COUNT(*) AS total " +
            "FROM userevents uev " +
            "JOIN events ev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id " +
            "WHERE uev.created_at >= :since " +
            "AND ev.type IN ('SUSPICIOUS_LOGIN', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS', " +
            "'TOKEN_REUSE_DETECTED', 'FEDERATED_LOGIN', 'RECOVERY_CODE_USED', 'SESSION_REVOKED') " +
            SCOPE_MARKER + " " +
            "GROUP BY ev.type";

    /**
     * The most recent anomaly-flagged sign-ins, newest first — the dashboard's central table.
     *
     * <p>Selects the {@code detail} column FR-TPF-1 writes ("a new device → step-up: EMAIL_CODE"),
     * which is the difference between a screen that says an anomaly happened and one that says
     * what was noticed and what the system did about it. The account is identified by name and
     * email because the audience is an administrator who can already list every account they
     * administer; the non-enumeration rule constrains what is told to <em>unauthenticated</em>
     * callers on error paths, and hiding identities from the person expected to act on them would
     * make the screen useless without protecting anything.
     */
    public static final String SELECT_RECENT_SUSPICIOUS_LOGINS_QUERY =
            "SELECT u.id AS user_id, u.first_name, u.last_name, u.email, " +
            "uev.device, uev.ip_address, uev.detail, uev.created_at " +
            "FROM userevents uev " +
            "JOIN events ev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id " +
            "WHERE ev.type = 'SUSPICIOUS_LOGIN' AND uev.created_at >= :since " +
            SCOPE_MARKER + " " +
            "ORDER BY uev.created_at DESC " +
            "LIMIT :limit";

    /**
     * Daily counts of the three login outcomes, for the trend chart.
     *
     * <p>Returns a long-format result (one row per day <em>per type</em>) and lets the service
     * pivot it. The alternative — conditional aggregation into one row per day with a column per
     * type — bakes the set of tracked types into the SQL, so adding a fourth series would mean
     * editing the query, the row mapper, and the model together.
     *
     * <p>Days with no activity are absent rather than zero: SQL has nothing to group for a day
     * that produced no rows. The service fills the gaps, because a chart that silently skips quiet
     * days compresses time and makes a burst look like a steady rate.
     */
    public static final String SELECT_DAILY_LOGIN_OUTCOMES_QUERY =
            "SELECT DATE(uev.created_at) AS day, ev.type AS event_type, COUNT(*) AS total " +
            "FROM userevents uev " +
            "JOIN events ev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id " +
            "WHERE uev.created_at >= :since " +
            "AND ev.type IN ('LOGIN_ATTEMPT_SUCCESS', 'LOGIN_ATTEMPT_FAILURE', 'SUSPICIOUS_LOGIN') " +
            SCOPE_MARKER + " " +
            "GROUP BY day, ev.type " +
            "ORDER BY day";

    /**
     * Accounts currently locked out or disabled, with the timestamp of their last failed sign-in.
     *
     * <p>Both states are returned together because both present the same way to the person
     * reporting the problem — "I can't get in" — while having different remedies (an unlock versus
     * an enable), and an administrator triaging a support request needs to see which one applies.
     *
     * <p>The correlated subquery for {@code last_failure_at} is the ordering key: a lockout from
     * this morning is operationally urgent and one from four months ago is housekeeping, and
     * without a timestamp the list arrives in an order that says nothing.
     */
    public static final String SELECT_RESTRICTED_ACCOUNTS_QUERY =
            "SELECT u.id AS user_id, u.first_name, u.last_name, u.email, u.non_locked, u.enabled, " +
            "(SELECT MAX(uev.created_at) FROM userevents uev " +
            " JOIN events ev ON ev.id = uev.event_id " +
            " WHERE uev.user_id = u.id AND ev.type = 'LOGIN_ATTEMPT_FAILURE') AS last_failure_at " +
            "FROM users u " +
            "WHERE (u.non_locked = FALSE OR u.enabled = FALSE) " +
            SCOPE_MARKER + " " +
            "ORDER BY last_failure_at IS NULL, last_failure_at DESC " +
            "LIMIT :limit";

    /**
     * Multi-factor adoption across the in-scope population, in one pass.
     *
     * <p>The three groups are counted with conditional sums rather than three separate queries so
     * they are guaranteed to add up to {@code total_users} — a percentage assembled from
     * separately-timed counts can exceed 100% and destroy trust in the whole screen.
     *
     * <p>{@code using_totp} takes precedence over {@code using_mfa} in the classification because
     * an account with both should be reported at its strongest factor, not double-counted.
     */
    public static final String SELECT_MFA_ADOPTION_QUERY =
            "SELECT COUNT(*) AS total_users, " +
            "SUM(CASE WHEN u.using_totp = TRUE THEN 1 ELSE 0 END) AS totp_users, " +
            "SUM(CASE WHEN u.using_totp = FALSE AND u.using_mfa = TRUE THEN 1 ELSE 0 END) AS sms_users, " +
            "SUM(CASE WHEN u.using_totp = FALSE AND u.using_mfa = FALSE THEN 1 ELSE 0 END) AS single_factor_users " +
            "FROM users u " +
            "WHERE 1 = 1 " +
            SCOPE_MARKER;

    /**
     * Live refresh sessions and how many distinct accounts hold them.
     *
     * <p>"Live" repeats the exact predicate {@code SessionService} uses — not revoked, not
     * superseded by rotation, not expired. A dashboard that counted sessions by a looser rule than
     * the one that decides whether a refresh succeeds would report devices as signed in that
     * cannot actually renew, which is the opposite of what the number is for.
     *
     * <p>The session count and the account count are both returned because their ratio is the
     * interesting signal: fifty sessions across forty-five accounts is ordinary, fifty across
     * three is not.
     */
    public static final String COUNT_ACTIVE_SESSIONS_QUERY =
            "SELECT COUNT(*) AS active_sessions, COUNT(DISTINCT rs.user_id) AS accounts_with_sessions " +
            "FROM refreshsessions rs " +
            "JOIN users u ON u.id = rs.user_id " +
            "WHERE rs.revoked = FALSE AND rs.superseded = FALSE AND rs.expires_at > NOW() " +
            SCOPE_MARKER;
}
