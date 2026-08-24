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
     * Fired when the user replaces their entire recovery-code batch on demand (after
     * proving possession with a live TOTP or recovery code), without disabling and
     * re-enrolling the authenticator. All previously issued codes are invalidated —
     * security-relevant for the same reason TOTP_DISABLED is: it changes what can get
     * back into the account.
     */
    RECOVERY_CODES_REGENERATED("You regenerated your recovery codes :)"),
    /**
     * Fired when an administrator force-disables a managed user's authenticator MFA
     * ({@code TotpService#adminResetTotp}) — the escape hatch for an account that has lost both
     * its authenticator and every recovery code and so has no live code to present through the
     * self-service {@code TOTP_DISABLED} path. Deliberately its own event, not a reuse of
     * TOTP_DISABLED, so the audit trail shows this was administrator-initiated rather than
     * self-service — the same reason PASSKEY_REMOVED is distinct from a user's own passkey
     * management.
     */
    MFA_RESET("An administrator reset your authenticator MFA :)"),
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
    SUSPICIOUS_LOGIN("We noticed a sign-in that didn't match your usual device or location, so we asked for extra verification :|"),
    /**
     * Fired when the user connects an identity provider to their account from the Security
     * Center (ROADMAP §1.4). Audited separately from {@link #PROFILE_UPDATE} because adding a
     * sign-in method is a credential change, not a profile edit — it belongs in the same class
     * of events as enrolling a second factor, and a user reviewing their activity log needs to
     * be able to spot one they did not perform.
     */
    PROVIDER_LINKED("You connected an identity provider to your account :)"),
    /**
     * Fired when the user disconnects an identity provider. Recorded for the same reason as
     * {@link #PROVIDER_LINKED}: removing a way into the account is as security-relevant as
     * adding one.
     */
    PROVIDER_UNLINKED("You disconnected an identity provider from your account :|"),
    /**
     * Fired when the user completes registering a new passkey (WebAuthn credential) from the
     * Account Security Center. Distinct from {@link #MFA_UPDATE}/{@link #TOTP_ENROLLED} because a
     * passkey is a standalone sign-in credential, not a second factor stacked on a password.
     */
    PASSKEY_REGISTERED("You registered a new passkey for signing in :)"),
    /**
     * Fired when a passkey is deleted — either by the account owner from the Security Center, or
     * by an administrator revoking a lost/compromised credential (SRS FR-ADMIN). There is no
     * "reset" for a passkey: the private key never leaves the authenticator, so revocation is the
     * only lever anyone (including an admin) has.
     */
    PASSKEY_REMOVED("You removed a passkey from your account :|"),
    /**
     * Fired when a sign-in completes via a passkey (usernameless WebAuthn assertion) instead of
     * {@link #LOGIN_ATTEMPT_SUCCESS}, mirroring {@link #FEDERATED_LOGIN}'s reasoning: the audit
     * trail should record WHICH authentication method was used, and a passkey is phishing-resistant
     * and device-bound enough that it is not stacked with risk-based step-up (see
     * {@code PasskeyController#verifyWebAuthn}).
     */
    PASSKEY_LOGIN("You signed in with a passkey :)"),
    /**
     * Fired when a new organization is created (Organization CRUD, FUTURE-ENHANCEMENTS.md §3.2).
     * Recorded against the organization itself via {@code organizationevents}, not against a
     * single user's {@code userevents} row.
     */
    ORG_CREATED("A new organization was created :)"),
    /**
     * Fired when an organization is renamed.
     */
    ORG_RENAMED("The organization was renamed :)"),
    /**
     * Fired when an organization is activated or deactivated (the retirement lever — see
     * {@link com.bob.angularspringbootfullstack.model.Organization}'s Javadoc for why there is no
     * hard delete).
     */
    ORG_STATUS_CHANGED("The organization's status was changed :|"),
    /**
     * Fired when an organization's profile fields (description, contact email, website) are
     * updated.
     */
    ORG_PROFILE_UPDATED("The organization's profile was updated :)"),
    /**
     * Fired when a member is added to (or reactivated within) an organization.
     */
    ORG_MEMBER_ADDED("A member was added to the organization :)"),
    /**
     * Fired when a member is removed from an organization.
     */
    ORG_MEMBER_REMOVED("A member was removed from the organization :|"),
    /**
     * Fired when a member's role is changed from within the organization's own member panel —
     * distinct from the plain {@link #ROLE_UPDATE} so the organization's own activity log shows
     * the change alongside its other membership events, not just the target user's personal log.
     */
    ORG_MEMBER_ROLE_CHANGED("A member's role within the organization was changed :)"),
    /**
     * Fired when an invite link is created for an organization (self-service member onboarding).
     */
    ORG_INVITE_CREATED("An invite link was created for the organization :)"),
    /**
     * Fired when an invite link is redeemed and its recipient joins the organization.
     */
    ORG_INVITE_REDEEMED("An invite link was redeemed to join the organization :)"),
    /**
     * Fired when an outstanding invite link is revoked before it is redeemed.
     */
    ORG_INVITE_REVOKED("An invite link for the organization was revoked :|");

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
