package com.bob.angularspringbootfullstack.enumeration;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One second/sign-in factor an organization may permit its members to enroll in — the vocabulary
 * behind {@code organizations.mfa_allowed_methods} (a CSV of these names; {@code NULL}/empty means
 * the organization has not configured a policy at all, not "no methods allowed" — see
 * {@link com.bob.angularspringbootfullstack.service.OrganizationService#isMfaMethodAllowed}).
 *
 * <p>Unlike {@link OrgRole}, this is not a ladder — there is no ordering, an organization simply
 * permits a <em>set</em> of methods, so this enum carries {@link #parseCsv} / {@link #toCsv} instead
 * of {@code OrgRole}'s tier comparisons.
 *
 * <p>{@link #SMS} and {@link #PHONE_CALL} are exposed as two independently selectable methods
 * because that is how an organization admin thinks about them, but the application has only one
 * underlying enrollment ({@code users.using_mfa}, toggled by {@code UserServiceImpl#toggleMFA}) —
 * Twilio Verify tries SMS delivery first and falls back to a voice call automatically on failure
 * ({@code NotificationServiceImpl#sendTwoFactorCode}), a choice the app makes at delivery time, not
 * one the user makes at enrollment time. Enforcement therefore treats them as satisfying each other:
 * enabling is permitted if <em>either</em> is in the organization's allowed set.
 *
 * <p>{@link #EMAIL_OTP} is deliberately excluded from enrollment-time enforcement even though it is
 * listed here: it is not a deliberate enrollment at all, but {@link StepUpMethod#EMAIL_CODE}, the
 * automatic step-up fallback for an account with no enrolled second factor. Restricting it would mean
 * changing login-time step-up behavior, which is the same open question the "none" policy value's
 * "must meet specific requirements" already defers — see FUTURE-ENHANCEMENTS.md.
 */
public enum OrgMfaMethod {
    /** Authenticator-app (TOTP) enrollment — {@code TotpServiceImpl#confirmEnrollment}. */
    TOTP,
    /** Passkey (WebAuthn) registration — {@code PasskeyServiceImpl#finishRegistration}. */
    PASSKEY,
    /** Emailed one-time code — see this enum's Javadoc for why it is not enrollment-enforced. */
    EMAIL_OTP,
    /** SMS-delivered one-time code — shares one enrollment with {@link #PHONE_CALL}. */
    SMS,
    /** Voice-call-delivered one-time code — shares one enrollment with {@link #SMS}. */
    PHONE_CALL;

    /**
     * Resolves a method name case-insensitively and null-safely, fail-closed like
     * {@link OrgRole#from(String)}: an unrecognized name yields an empty {@link Optional} rather
     * than guessing.
     *
     * @param name the method name to resolve; may be null or blank
     * @return the matching constant, or empty if null, blank, or unknown
     */
    public static Optional<OrgMfaMethod> from(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String normalized = name.trim().toUpperCase();
        return Arrays.stream(values()).filter(method -> method.name().equals(normalized)).findFirst();
    }

    /**
     * Parses a stored CSV of method names into the set of methods actually recognized.
     *
     * <p>An unrecognized token is dropped rather than rejected — the same "unreadable means absent"
     * convention {@link OrgRole#findOrgRole} applies to a stray database value, since the column
     * carries no CHECK constraint of its own to guarantee every stored token is still valid.
     *
     * @param csv the stored value, e.g. {@code "TOTP,PASSKEY,SMS"}; may be null or blank
     * @return the recognized methods, empty (never null) if the input is null, blank, or
     *         recognizes nothing
     */
    public static Set<OrgMfaMethod> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<OrgMfaMethod> methods = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            from(token).ifPresent(methods::add);
        }
        return methods;
    }

    /**
     * Serializes a set of methods back to the stored CSV form, sorted by name for a deterministic
     * column value regardless of the caller's set implementation/insertion order.
     *
     * @param methods the methods to serialize; a null or empty collection yields an empty string
     * @return the CSV form, e.g. {@code "PASSKEY,SMS,TOTP"}, or {@code ""} for no methods
     */
    public static String toCsv(Collection<OrgMfaMethod> methods) {
        if (methods == null || methods.isEmpty()) return "";
        return new TreeSet<>(methods).stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
