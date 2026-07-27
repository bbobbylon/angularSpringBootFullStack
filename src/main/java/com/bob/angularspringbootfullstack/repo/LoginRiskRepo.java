package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.LoginContext;

import java.util.List;

/**
 * Read-only data-access contract for the login-anomaly baseline (SRS FR-TPF-1).
 *
 * <p>Kept separate from {@link EventRepo} even though both read {@code userevents}: {@code EventRepo}
 * serves the user-facing Activity Log (fully-resolved {@code UserEvent}s with descriptions and
 * timestamps for display), whereas this repository answers one narrow security question — "which
 * device/network fingerprints has this account signed in from before?" — and returns a minimal
 * projection tuned for set membership. Splitting them keeps the audit-display query free of
 * security-evaluation concerns and vice versa.
 *
 * <p>There is intentionally no write side: anomaly detection <em>records</em> its findings through
 * the normal audit path (a {@code SUSPICIOUS_LOGIN}
 * {@link com.bob.angularspringbootfullstack.event.NewUserEvent}), so every audit write in the
 * application still funnels through the single
 * {@link com.bob.angularspringbootfullstack.listener.NewUserEventListener} seam.
 */
public interface LoginRiskRepo {

    /**
     * Returns the distinct device / IP fingerprints this user has previously signed in from,
     * most-recently-used first.
     *
     * @param userId the account whose sign-in history forms the baseline
     * @param limit  maximum number of <em>distinct</em> fingerprints to return
     * @return the history, newest first; empty when the account has never completed a sign-in
     */
    List<LoginContext> findRecentLoginContexts(Long userId, int limit);
}
