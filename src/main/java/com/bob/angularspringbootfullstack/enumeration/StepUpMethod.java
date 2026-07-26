package com.bob.angularspringbootfullstack.enumeration;

import lombok.Getter;

/**
 * Which second factor a risk-flagged sign-in was escalated to (SRS FR-TPF-1).
 *
 * <p>The ordering here mirrors the precedence in
 * {@link com.bob.angularspringbootfullstack.controller.UserController#login}: an account that
 * already carries a stronger factor is challenged with it, and the emailed one-time code exists
 * only as the fallback for accounts that have no second factor enrolled at all. That means a
 * risk-flagged login always faces <em>some</em> proof of possession before tokens are issued.
 *
 * <p>The constant name is what lands in the audit row's {@code detail} column, so the security
 * dashboard can distinguish "we caught something and made them use their authenticator" from "we
 * caught something and had to fall back to email".
 */
@Getter
public enum StepUpMethod {

    /** The account has a confirmed authenticator app; the existing TOTP challenge is the step-up. */
    TOTP(true),

    /** The account has SMS 2FA enabled; the existing SMS code path is the step-up. */
    SMS_CODE(true),

    /**
     * The account has no enrolled second factor, so a one-time code is emailed to the address on
     * file. This is the branch FR-TPF-1 adds: without it a risk-flagged single-factor account would
     * have nothing standing between a stolen password and a session.
     */
    EMAIL_CODE(false),

    /** No escalation applied — the sign-in was not flagged. */
    NONE(false);

    /**
     * -- GETTER --
     * Whether the user was already going to be challenged regardless of the risk verdict.
     * <p>
     * Drives the notification decision: when true the challenge itself reaches the user on a
     * channel they control, but it carries no explanation, so a separate security-alert email is
     * sent. When false, the step-up email <em>is</em> the notification and carries the reason
     * inline — so exactly one message goes out either way.
     */
    private final boolean alreadyChallenged;

    StepUpMethod(boolean alreadyChallenged) {
        this.alreadyChallenged = alreadyChallenged;
    }
}
