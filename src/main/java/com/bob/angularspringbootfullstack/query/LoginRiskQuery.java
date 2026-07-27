package com.bob.angularspringbootfullstack.query;

/**
 * SQL query constants backing the login-anomaly check (SRS FR-TPF-1).
 *
 * <p>Deliberately read-only and deliberately schema-free: anomaly detection needs no new table
 * because the {@code userevents} audit log already records the device and IP address of every
 * successful sign-in. This class simply reads that existing history back as the behavioural
 * baseline for one account.
 *
 * <p>Only <em>successful</em> authentications count toward the baseline. A failed attempt says
 * nothing about where the legitimate user signs in from, and letting failures seed the baseline
 * would hand an attacker a way to "teach" the system their own device: fail once from the new
 * machine, and the subsequent real login would look familiar.
 *
 * @see com.bob.angularspringbootfullstack.repo.repoimpl.LoginRiskRepoImpl
 */
public class LoginRiskQuery {

    /**
     * Returns the distinct device / IP pairs this user has previously signed in from, newest first.
     *
     * <p>Included event types are the three that represent a completed authentication:
     * {@code LOGIN_ATTEMPT_SUCCESS} (password or step-up/2FA completion — the verify-code endpoint
     * publishes it too), {@code FEDERATED_LOGIN} (an OAuth2/OIDC sign-in), and
     * {@code RECOVERY_CODE_USED} (a TOTP recovery-code sign-in). {@code LOGIN_ATTEMPT} is excluded
     * because it fires <em>before</em> the outcome is known, so it would record the devices of
     * failed attempts as well.
     *
     * <p>{@code MAX(uev.created_at)} paired with {@code GROUP BY} collapses a chatty history (a
     * daily user accumulates hundreds of identical rows) down to one row per distinct fingerprint
     * while still ordering by most-recent use, so {@code :limit} bounds <em>distinct</em>
     * fingerprints rather than raw rows — a user cannot age their own baseline out simply by
     * signing in repeatedly from one machine.
     */
    public static final String SELECT_RECENT_LOGIN_CONTEXTS_BY_USER_ID_QUERY =
            "SELECT uev.device, uev.ip_address, MAX(uev.created_at) AS last_seen " +
            "FROM userevents uev " +
            "JOIN events ev ON ev.id = uev.event_id " +
            "WHERE uev.user_id = :userId " +
            "AND ev.type IN ('LOGIN_ATTEMPT_SUCCESS', 'FEDERATED_LOGIN', 'RECOVERY_CODE_USED') " +
            "GROUP BY uev.device, uev.ip_address " +
            "ORDER BY last_seen DESC " +
            "LIMIT :limit";
}
