package com.bob.angularspringbootfullstack.utils;

import java.util.Arrays;

/**
 * Derives the admin-facing user-type badge (P2-1): INTERNAL, EXTERNAL, or FEDERATED.
 * <p>
 * FEDERATED is read straight off {@link com.bob.angularspringbootfullstack.model.User#getOrigin()}
 * — an immutable fact stamped once, at account creation, by
 * {@code FederatedIdentityServiceImpl#insertFederatedUser}. INTERNAL vs EXTERNAL is NOT stored;
 * it is derived fresh on every read from the account's email domain against an env-driven
 * allowlist, so changing which domains count as "internal" takes effect immediately for every
 * existing account and needs no backfill or redeploy of application code — only a config change.
 * <p>
 * Pure static logic, deliberately not a Spring bean, for the same reason
 * {@link RequestUtils}-style helpers and {@code RoleType#canAssign} are: the decision is testable
 * in isolation without standing up any Spring context.
 */
public final class UserTypeResolver {

    public static final String INTERNAL = "INTERNAL";
    public static final String EXTERNAL = "EXTERNAL";
    public static final String FEDERATED = "FEDERATED";

    private UserTypeResolver() {
    }

    /**
     * Resolves one account's user type.
     *
     * @param email              the account's email address
     * @param origin             the account's stamped {@code origin} column value, or {@code null}
     *                           for a password-registered account
     * @param internalDomainsCsv comma-separated allowlist (env {@code INTERNAL_DOMAINS}), e.g.
     *                           {@code "lewisu.edu, tesseraapp.dev"}; blank/{@code null} means
     *                           nothing qualifies as INTERNAL
     * @return {@link #FEDERATED}, {@link #INTERNAL}, or {@link #EXTERNAL}
     */
    public static String resolve(String email, String origin, String internalDomainsCsv) {
        if (origin != null && origin.startsWith("FEDERATED_")) {
            return FEDERATED;
        }
        return isInternalDomain(email, internalDomainsCsv) ? INTERNAL : EXTERNAL;
    }

    /**
     * Whether the email's domain appears (case-insensitively) in the comma-separated allowlist.
     *
     * @param email              the address to check
     * @param internalDomainsCsv comma-separated allowlist; blank/{@code null} means no domain qualifies
     * @return true only when the email has a domain and it matches an allowlist entry exactly
     */
    static boolean isInternalDomain(String email, String internalDomainsCsv) {
        if (email == null || internalDomainsCsv == null || internalDomainsCsv.isBlank()) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase();
        return Arrays.stream(internalDomainsCsv.split(","))
                .map(String::trim)
                .filter(candidate -> !candidate.isEmpty())
                .map(String::toLowerCase)
                .anyMatch(domain::equals);
    }
}
