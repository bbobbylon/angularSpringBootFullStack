package com.bob.angularspringbootfullstack.enumeration;


import lombok.Getter;

/**
 * Categorizes every type of noteworthy action that can happen on a user account.
 *
 * <p>Each constant carries a human-readable description that is stored in the
 * {@code events} reference table and shown in the Activity Logs UI.  When a
 * user action occurs (login, password change, etc.), the matching constant is
 * published as a {@link com.bob.angularspringbootfullstack.event.NewUserEvent}
 * so the listener can write an audit row to the {@code userevents} table.
 */

@Getter
public enum EventType {
    /**
     * Fired at the start of every login attempt, before success or failure is known.
     */
    LOGIN_ATTEMPT("You tried to log-in :)"),
    /**
     * Fired when authentication succeeds and the account passes all lock/enable checks.
     */
    LOGIN_ATTEMPT_SUCCESS("You attempted to log-in and you succeeded :)"),
    /**
     * Fired when credentials are wrong, or the account is locked or disabled.
     */
    LOGIN_ATTEMPT_FAILURE("You tried to log-in, but you failed to do so :("),
    /**
     * Fired when the user saves changes to their name, email, bio, or other profile fields.
     */
    PROFILE_UPDATE("You have updated your profile information :)"),
    /**
     * Fired when the user uploads a new profile picture.
     */
    PROFILE_PICTURE_UPDATE("You have updated your profile picture :)"),
    /**
     * Fired when the user's role is reassigned.
     */
    ROLE_UPDATE("You have updated your role and permissions :)"),
    /**
     * Fired when the account's enabled or non-locked flags are toggled.
     */
    ACCOUNT_SETTINGS_UPDATE("You have updated your account settings :)"),
    /**
     * Fired when the user successfully changes their password.
     */
    PASSWORD_UPDATE("You have updated your password successfully :)"),
    /**
     * Fired when the user enables or disables multifactor authentication.
     */
    MFA_UPDATE("You have updated your multi-factor authentication settings :)"),
    /**
     * Fired when a user signs in through a federated identity provider (Google,
     * GitHub, or Microsoft) and the token-exchange point issues application JWTs
     * (SRS FR-FED-4/5). Distinct from LOGIN_ATTEMPT_SUCCESS so the audit trail
     * records the authentication method.
     */
    FEDERATED_LOGIN("You logged in with a federated identity provider :)"),
    /**
     * Fired when the user completes authenticator-app enrollment — the moment the
     * pending TOTP secret is confirmed and recovery codes are issued (SRS FR-MFA-4).
     * Distinct from MFA_UPDATE (the SMS toggle) so the audit trail records WHICH
     * second factor changed.
     */
    TOTP_ENROLLED("You enrolled an authenticator app for multi-factor authentication :)"),
    /**
     * Fired when the user removes their authenticator app (after proving possession
     * with a live TOTP or recovery code), returning the account to SMS MFA or
     * single-factor.
     */
    TOTP_DISABLED("You removed your authenticator app from multi-factor authentication :)"),
    /**
     * Fired when a single-use recovery code (rather than a live authenticator code)
     * satisfies a login challenge — security-relevant because each use permanently
     * burns one of the user's fallback codes.
     */
    RECOVERY_CODE_USED("You signed in using a single-use recovery code :)"),
    /**
     * Fired when the user revokes one of their active sessions (or all other sessions
     * via "log out everywhere") from the Account Security Center (plan.md M5). The
     * revoked family can no longer refresh; its in-flight access token simply ages out
     * within its 30-minute TTL.
     */
    SESSION_REVOKED("You revoked an active session on your account :)"),
    /**
     * Fired when a refresh token that was already rotated (or revoked) is presented
     * again — the signature of token theft. The whole session family is revoked in
     * response, forcing a fresh first-factor login (FR-JWT-5 reuse detection).
     */
    TOKEN_REUSE_DETECTED("A previously used refresh token was replayed; the affected session family was revoked for your security :|"),
    /**
     * Fired when a sign-in passes its first factor but does not match the account's own history
     * of devices and network locations (SRS FR-TPF-1). The login is escalated to step-up
     * re-verification rather than refused, so this event records a <em>challenge</em>, not a
     * rejection — a legitimate user on a new laptop produces one of these every time.
     *
     * <p>The row's {@code detail} column carries which signals fired and which step-up applied
     * (e.g. {@code "a new device → step-up: EMAIL_CODE"}), which is what lets the security
     * dashboard distinguish "caught and handled with an authenticator" from "caught and fell back
     * to an emailed code".
     */
    SUSPICIOUS_LOGIN("We noticed a sign-in that didn't match your usual device or location, so we asked for extra verification :|");

    /**
     * -- GETTER --
     * Returns the plain-English description shown to the user in the Activity Logs UI.
     *
     */
    private final String description;

    EventType(String description) {
        this.description = description;
    }

}
