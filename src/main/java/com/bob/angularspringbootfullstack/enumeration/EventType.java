package com.bob.angularspringbootfullstack.enumeration;

/**
 * Categorizes every type of noteworthy action that can happen on a user account.
 *
 * <p>Each constant carries a human-readable description that is stored in the
 * {@code events} reference table and shown in the Activity Logs UI.  When a
 * user action occurs (login, password change, etc.), the matching constant is
 * published as a {@link com.bob.angularspringbootfullstack.event.NewUserEvent}
 * so the listener can write an audit row to the {@code userevents} table.
 */
public enum EventType {
    /** Fired at the start of every login attempt, before success or failure is known. */
    LOGIN_ATTEMPT("You tried to log-in :)"),
    /** Fired when authentication succeeds and the account passes all lock/enable checks. */
    LOGIN_ATTEMPT_SUCCESS("You attempted to log-in and you succeeded :)"),
    /** Fired when credentials are wrong, or the account is locked or disabled. */
    LOGIN_ATTEMPT_FAILURE("You tried to log-in, but you failed to do so :("),
    /** Fired when the user saves changes to their name, email, bio, or other profile fields. */
    PROFILE_UPDATE("You have updated your profile information :)"),
    /** Fired when the user uploads a new profile picture. */
    PROFILE_PICTURE_UPDATE("You have updated your profile picture :)"),
    /** Fired when the user's role is reassigned. */
    ROLE_UPDATE("You have updated your role and permissions :)"),
    /** Fired when the account's enabled or non-locked flags are toggled. */
    ACCOUNT_SETTINGS_UPDATE("You have updated your account settings :)"),
    /** Fired when the user successfully changes their password. */
    PASSWORD_UPDATE("You have updated your password successfully :)"),
    /** Fired when the user enables or disables multi-factor authentication. */
    MFA_UPDATE("You have updated your multi-factor authentication settings :)");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    /**
     * Returns the plain-English description shown to the user in the Activity Logs UI.
     *
     * @return the human-readable description for this event type
     */
    public String getDescription() {
        return this.description;
    }
}
