package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.SecurityOverview;

import java.util.Collection;

/**
 * Business contract for the administrative security dashboard (SRS FR-TPF-2).
 *
 * <p>FR-TPF-1 gave the system the ability to notice a sign-in that does not match an account's
 * history, and to escalate it. What it did not give anyone was a way to <em>see</em> that
 * happening: the evidence went into the audit log and stayed there. This service is the read side
 * of that feature — it turns the {@code SUSPICIOUS_LOGIN} trail, the account-state flags, the MFA
 * enrollment columns, and the live session table into one picture of the platform's security
 * posture.
 *
 * <p>The service owns three decisions the repository deliberately does not: how far back to look
 * (and the clamping of a caller-supplied window), what an empty organization scope means, and how
 * the sparse per-day counts become a dense, gap-free trend. Those are policy, and policy does not
 * belong in SQL.
 */
public interface SecurityDashboardService {

    /**
     * Assembles the complete dashboard for one administrator.
     *
     * @param organizationIds the caller's scope (FR-ORG-2): {@code null} for an unscoped
     *                        administrator who sees the whole system, a non-empty collection to
     *                        restrict to those organizations, or an <em>empty</em> collection for an
     *                        organization admin with no active memberships — who sees nothing at
     *                        all, not everything
     * @param windowDays      how many days of history to summarize; clamped by the implementation
     *                        to a sane range, since it arrives from a query parameter
     * @param suspiciousLoginsPage    0-based page of the flagged sign-ins table; negatives are
     *                                clamped to 0. Indexes past the end return an empty list with
     *                                honest metadata rather than snapping to the last page, so the
     *                                pager never misreports where it is
     * @param suspiciousLoginsSize    rows per page for that table; clamped by the implementation to
     *                                a sane range, since it too arrives from a query parameter. The
     *                                clamped value is reported back in the table's {@code PageInfo},
     *                                so a caller never has to guess what they actually got
     * @param restrictedAccountsPage  0-based page of the locked/disabled accounts table, same rules
     * @param restrictedAccountsSize  rows per page for that table, same rules. Separate from the
     *                                flagged-sign-ins size for the same reason the page indexes are
     *                                separate: the two tables answer unrelated questions and are
     *                                read at unrelated rates
     * @return the fully assembled overview, never {@code null}
     */
    SecurityOverview getOverview(Collection<Long> organizationIds, int windowDays,
                                 int suspiciousLoginsPage, int suspiciousLoginsSize,
                                 int restrictedAccountsPage, int restrictedAccountsSize);
}
