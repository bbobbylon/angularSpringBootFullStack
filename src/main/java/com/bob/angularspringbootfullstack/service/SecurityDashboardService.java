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
 * enrolment columns, and the live session table into one picture of the platform's security
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
     * @param windowDays      how many days of history to summarise; clamped by the implementation
     *                        to a sane range, since it arrives from a query parameter
     * @return the fully assembled overview, never {@code null}
     */
    SecurityOverview getOverview(Collection<Long> organizationIds, int windowDays);
}
