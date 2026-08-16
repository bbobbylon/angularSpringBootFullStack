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
                               PageInfo suspiciousLoginsPage,
                               List<LoginOutcomeTrendPoint> trend,
                               List<RestrictedAccount> restrictedAccounts,
                               PageInfo restrictedAccountsPage,
                               MfaAdoption mfaAdoption,
                               long activeSessions,
                               long accountsWithSessions) {

    /**
     * Pagination metadata for one of the dashboard's two growing tables.
     *
     * <h3>Why this exists at all</h3>
     * Both {@code suspiciousLogins} and {@code restrictedAccounts} were previously fetched with a
     * bare {@code LIMIT} and no total, so the screen showed the newest N rows and said nothing about
     * the remainder. On an audit surface that is the one ambiguity you cannot ship: "three flagged
     * sign-ins this week" and "the three most recent of three hundred" call for entirely different
     * responses, and the rendered table looked identical either way.
     *
     * <h3>Why a nested record rather than flat fields</h3>
     * Four more components per table would have pushed {@link SecurityOverview} to fifteen
     * positional arguments, where a transposed pair of {@code int}s compiles happily and reports the
     * wrong page. Grouping them keeps each table's metadata addressable as a unit — and serializes
     * to a nested JSON object the Angular pager can bind to directly, instead of six loose keys the
     * template has to reassemble.
     *
     * @param page          the 0-based index of the page contained in the sibling list
     * @param size          rows per page, echoed so the client need not assume the server's default
     * @param totalElements total matching rows, ignoring pagination
     * @param totalPages    total pages at this size; 0 when there are no rows at all
     */
    public record PageInfo(int page, int size, long totalElements, int totalPages) {

        /**
         * Derives {@code totalPages} rather than trusting a caller to compute it consistently.
         *
         * @param page          the 0-based page index being returned
         * @param size          rows per page; guarded against zero so this cannot divide by zero
         * @param totalElements total matching rows
         * @return the populated metadata
         */
        public static PageInfo of(int page, int size, long totalElements) {
            return new PageInfo(page, size, totalElements,
                    (int) Math.ceil((double) totalElements / Math.max(size, 1)));
        }

        /**
         * The metadata for a table with no rows — used by {@link SecurityOverview#empty(int)}.
         *
         * @param size the page size that would have applied, so the client's controls stay stable
         * @return zeroed metadata describing an empty table
         */
        public static PageInfo empty(int size) {
            return new PageInfo(0, size, 0L, 0);
        }
    }

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
        return new SecurityOverview(windowDays, true, Map.of(),
                List.of(), PageInfo.empty(0),
                List.of(),
                List.of(), PageInfo.empty(0),
                new MfaAdoption(0, 0, 0, 0), 0, 0);
    }
}
