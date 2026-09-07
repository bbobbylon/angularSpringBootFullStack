package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.AuditChainVerificationResult;

/**
 * On-demand recomputation of the {@code userevents} and {@code organizationevents} tamper-evidence
 * hash chains (FUTURE-ENHANCEMENTS §3.1), backing the "Verify audit trail integrity" action on the
 * security dashboard.
 *
 * <p>Deliberately not run on a schedule or at startup: the backlog item asks for something
 * "verifiable on demand", and a chain over an append-only, ever-growing audit table gets more
 * expensive to walk every day it exists — a background job would either re-walk the whole table
 * repeatedly for no new information, or need its own checkpointing to avoid that, which is exactly
 * the complexity this "lightweight" feature was scoped to avoid. An administrator who suspects
 * tampering (or simply wants reassurance) triggers a check when they want one.
 */
public interface AuditIntegrityService {

    /**
     * Re-walks the {@code userevents} hash chain in ascending {@code id} order.
     *
     * @return whether the chain is intact, and where it first breaks if not
     */
    AuditChainVerificationResult verifyUserEvents();

    /**
     * Re-walks the {@code organizationevents} hash chain in ascending {@code id} order.
     *
     * @return whether the chain is intact, and where it first breaks if not
     */
    AuditChainVerificationResult verifyOrganizationEvents();
}
