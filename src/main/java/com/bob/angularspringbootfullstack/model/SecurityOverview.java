package com.bob.angularspringbootfullstack.model;

import java.util.List;
import java.util.Map;

/**
 * The complete payload of the administrative security dashboard (SRS FR-TPF-2).
 *
 * <h3>Why one composite instead of six endpoints</h3>
 * Every part of this screen answers the same question — "is anything wrong right now, and where?"
 * — over the same window and the same organization scope. Served as separate endpoints, the six
 * panels would be assembled from six different instants: a suspicious sign-in could appear in the
 * activity table while the counter above it still read zero, and an administrator would have no
 * way to know which panel was stale. One request means one consistent picture, and the SPA gets a
 * single loading state instead of six independently-failing ones.
 *
 * <p>The counterargument — that a dashboard should stream its cheap panels first — does not apply
 * at this data volume: these are indexed aggregates over an audit table, not a report.
 *
 * <h3>Scope is part of the payload, not just an input</h3>
 * {@link #scoped} tells the SPA whether it is looking at the whole system or at one
 * administrator's organizations. A dashboard that renders identically in both cases invites its
 * most dangerous misreading — an organization admin concluding the platform is quiet when they can
 * only see their own slice of it. The flag exists so the UI can say which it is.
 *
 * @param windowDays        how many days of history the counters and trend cover
 * @param scoped            true when these figures are restricted to the caller's organizations
 *                          (FR-ORG-2), false when they are system-wide
 * @param eventCounts       totals per security event type over the window, keyed by
 *                          {@code EventType} name; types with no occurrences are present with a
 *                          zero rather than absent, so the UI never has to distinguish "none" from
 *                          "not reported"
 * @param suspiciousLogins  the most recent anomaly-flagged sign-ins, newest first
 * @param trend             per-day login outcomes across the window, oldest first, gap-filled
 * @param restrictedAccounts accounts currently locked or disabled, most recent failure first
 * @param mfaAdoption       second-factor coverage across the in-scope population
 * @param activeSessions    live refresh sessions in scope
 * @param accountsWithSessions distinct accounts holding at least one live session — meaningful
 *                             only alongside {@code activeSessions}, since it is their ratio that
 *                             distinguishes ordinary multi-device use from something odd
 */
public record SecurityOverview(int windowDays,
                               boolean scoped,
                               Map<String, Long> eventCounts,
                               List<SuspiciousLoginEntry> suspiciousLogins,
                               List<LoginOutcomeTrendPoint> trend,
                               List<RestrictedAccount> restrictedAccounts,
                               MfaAdoption mfaAdoption,
                               long activeSessions,
                               long accountsWithSessions) {

    /**
     * The overview an administrator sees when their organization scope is empty (FR-ORG-2).
     *
     * <p>Empty scope means <em>nothing</em>, never <em>everything</em>. An organization admin whose
     * memberships have all lapsed must see zeros, because the alternative — falling back to the
     * unscoped view — would hand the system-wide security picture to precisely the account with the
     * least established standing. This factory exists so that decision is made once and named,
     * rather than reimplemented as a scattering of empty literals at each call site.
     *
     * @param windowDays the window the (empty) figures nominally cover, so the UI still labels itself
     * @return an overview whose every figure is zero or empty, flagged as scoped
     */
    public static SecurityOverview empty(int windowDays) {
        return new SecurityOverview(windowDays, true, Map.of(), List.of(), List.of(), List.of(),
                new MfaAdoption(0, 0, 0, 0), 0, 0);
    }
}
