package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.DailyEventCount;
import com.bob.angularspringbootfullstack.model.MfaAdoption;
import com.bob.angularspringbootfullstack.model.RestrictedAccount;
import com.bob.angularspringbootfullstack.model.SessionActivity;
import com.bob.angularspringbootfullstack.model.SuspiciousLoginEntry;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-only data access for the administrative security dashboard (SRS FR-TPF-2).
 *
 * <h3>The organizationIds convention</h3>
 * Every method takes an {@code organizationIds} collection with a three-way meaning that is shared
 * across the whole FR-ORG-2 surface:
 * <ul>
 *   <li>{@code null} — the caller is unscoped ({@code ROLE_ADMIN} / {@code ROLE_APPLICATION_ADMIN})
 *       and sees the whole system;</li>
 *   <li>non-empty — the caller sees only accounts belonging to these organizations;</li>
 *   <li>empty — <b>never passed here</b>. An empty scope means the caller may see nothing, and the
 *       service short-circuits to {@link com.bob.angularspringbootfullstack.model.SecurityOverview#empty}
 *       before reaching this layer. Passing it through would produce {@code IN ()}, which is not
 *       valid SQL, so the interface would fail loudly rather than quietly return everything — but
 *       relying on a syntax error to enforce an access rule is not a control, so the decision is
 *       made explicitly upstream instead.</li>
 * </ul>
 *
 * <p>Every implementation is expected to fail <em>closed</em> and non-fatally: a dashboard panel
 * that cannot be read should degrade to empty with a logged warning, never propagate. This is a
 * reporting screen — the correct behaviour when one query breaks is five working panels and one
 * blank one, not a 500 that hides the other five.
 */
public interface SecurityDashboardRepo {

    /**
     * Totals per security-relevant event type since a cut-off.
     *
     * @param since           the oldest event timestamp to include
     * @param organizationIds the scope, per the class-level convention
     * @return event type name → count, containing only types that actually occurred
     */
    Map<String, Long> countSecurityEvents(LocalDateTime since, Collection<Long> organizationIds);

    /**
     * The most recent anomaly-flagged sign-ins (FR-TPF-1's {@code SUSPICIOUS_LOGIN} rows).
     *
     * @param since           the oldest event timestamp to include
     * @param limit           maximum rows to return, newest first
     * @param organizationIds the scope, per the class-level convention
     * @return the flagged sign-ins, newest first, empty when there are none
     */
    List<SuspiciousLoginEntry> findRecentSuspiciousLogins(LocalDateTime since, int limit, Collection<Long> organizationIds);

    /**
     * Per-day, per-type login outcome counts in long format, for the trend chart.
     *
     * <p>Days with no activity are simply absent — the caller gap-fills, since only it knows the
     * intended window boundaries.
     *
     * @param since           the oldest event timestamp to include
     * @param organizationIds the scope, per the class-level convention
     * @return one entry per (day, event type) pair that produced at least one event
     */
    List<DailyEventCount> findDailyLoginOutcomes(LocalDateTime since, Collection<Long> organizationIds);

    /**
     * Accounts that currently cannot sign in — locked by brute-force protection or not enabled.
     *
     * @param limit           maximum rows to return, most-recent failure first
     * @param organizationIds the scope, per the class-level convention
     * @return the restricted accounts, empty when every account is in good standing
     */
    List<RestrictedAccount> findRestrictedAccounts(int limit, Collection<Long> organizationIds);

    /**
     * Second-factor enrolment across the in-scope population.
     *
     * @param organizationIds the scope, per the class-level convention
     * @return the adoption breakdown; all zeros when the scope contains no accounts
     */
    MfaAdoption findMfaAdoption(Collection<Long> organizationIds);

    /**
     * Live refresh sessions and the number of distinct accounts holding them.
     *
     * @param organizationIds the scope, per the class-level convention
     * @return the session totals; zeros when nobody in scope is signed in
     */
    SessionActivity findSessionActivity(Collection<Long> organizationIds);
}
