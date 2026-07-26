package com.bob.angularspringbootfullstack.enumeration;

import lombok.Getter;

/**
 * The individual signals that can make a sign-in "risky" under FR-TPF-1.
 *
 * <p>Each constant is one independent observation about the current login compared against the
 * account's own history in the {@code userevents} audit log — never against other accounts, so a
 * risk decision can never become an enumeration oracle. A
 * {@link com.bob.angularspringbootfullstack.dto.LoginRiskAssessment} carries zero or more of these;
 * any non-empty set escalates the login to step-up re-verification in
 * {@link com.bob.angularspringbootfullstack.controller.UserController}.
 *
 * <p>The {@code label} is what gets written to the audit row's {@code detail} column and shown to
 * the user in the step-up email. It deliberately describes <em>what changed</em> ("a new device")
 * rather than naming the stored device or IP, so an audit row read by an administrator does not
 * hand out the user's device fingerprints or address history verbatim.
 */
@Getter
public enum LoginRiskReason {

    /**
     * The parsed {@code OS - Browser - Device} string has never appeared on a successful sign-in
     * for this account. Catches a genuinely new machine or browser — the strongest and cheapest
     * signal available from data this application already records.
     */
    NEW_DEVICE("a new device"),

    /**
     * The client's IP address falls outside every network this account has previously signed in
     * from. Compared at network-prefix granularity rather than exact IP (see
     * {@link com.bob.angularspringbootfullstack.service.serviceimpl.LoginRiskServiceImpl}), because
     * consumer ISPs rotate the final octet routinely and an exact-match rule would flag almost
     * every login as anomalous.
     */
    NEW_NETWORK("a new network location");

    /**
     * -- GETTER --
     * Returns the human-readable phrase used in audit details and step-up emails.
     */
    private final String label;

    LoginRiskReason(String label) {
        this.label = label;
    }
}
