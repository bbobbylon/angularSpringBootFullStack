package com.bob.angularspringbootfullstack.enumeration;

public enum EventType {
    LOGIN_ATTEMPT("You tried to log-in :)"),
    LOGIN_ATTEMPT_FAILURE("You tried to log-in, but you failed to do so :("),
    PROFILE_PICTURE_UPDATE("You have updated your profile picture :)"),
    ROLE_UPDATE("You have updated your role and permissions :)"),
    ACCOUNT_SETTINGS_UPDATE("You have updated your account settings :)"),
    MFA_UPDATE("You have updated your multi-factor authentication settings :)"),
    LOGIN_ATTEMPT_SUCCESS("You attempted to log-in and you succeeded :)"),
    PROFILE_UPDATE("You have updated your profile information :)"),
    PASSWORD_UPDATE("You have updated your password successfully :)");
    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return this.description;
    }
}
