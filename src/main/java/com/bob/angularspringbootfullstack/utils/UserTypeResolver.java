package com.bob.angularspringbootfullstack.utils;

import java.util.Arrays;

import static com.bob.angularspringbootfullstack.constants.Constants.SERVICE_ACCOUNT_ORIGIN;

/**
 * Derives the admin-facing user-type badge (P2-1): INTERNAL, EXTERNAL, FEDERATED, or
 * SERVICE_ACCOUNT.
 * <p>
 * FEDERATED and SERVICE_ACCOUNT are read straight off
 * {@link com.bob.angularspringbootfullstack.model.User#getOrigin()} — an immutable fact stamped
 * once, at account creation, by {@code FederatedIdentityServiceImpl#insertFederatedUser} or
 * {@code ServiceAccountServiceImpl#create} respectively. INTERNAL vs EXTERNAL is NOT stored; it is
 * derived fresh on every read from the account's email domain against an env-driven allowlist, so
 * changing which domains count as "internal" takes effect immediately for every existing account
 * and needs no backfill or redeploy of application code — only a config change.
 * <p>
 * Pure static logic, deliberately not a Spring bean, for the same reason
 * {@link RequestUtils}-style helpers and {@code RoleType#canAssign} are: the decision is testable
 * in isolation without standing up any Spring context.
 */
public final class UserTypeResolver {

    public static final String INTERNAL = "INTERNAL";
    public static final String EXTERNAL = "EXTERNAL";
    public static final String FEDERATED = "FEDERATED";
    public static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";

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
     * @return {@link #SERVICE_ACCOUNT}, {@link #FEDERATED}, {@link #INTERNAL}, or {@link #EXTERNAL}
     */
    public static String resolve(String email, String origin, String internalDomainsCsv) {
        // Checked before FEDERATED_: SERVICE_ACCOUNT_ORIGIN doesn't share that prefix, but a
        // synthetic service-account email would otherwise fall through to an EXTERNAL/INTERNAL
        // guess based on its @service.tessera.internal domain, which is not a meaningful badge
        // for a machine account.
        if (SERVICE_ACCOUNT_ORIGIN.equals(origin)) {
            return SERVICE_ACCOUNT;
        }
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
