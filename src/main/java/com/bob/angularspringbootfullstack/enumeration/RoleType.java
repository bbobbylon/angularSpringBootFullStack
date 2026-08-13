package com.bob.angularspringbootfullstack.enumeration;

import java.util.Arrays;
import java.util.Optional;

/**
 * RoleType defines the available user roles in the system.
 *
 * <p>Each constant represents a role that can be assigned to users for role-based access control
 * (RBAC). Spring Security enforces authorization from the comma-separated permission string these
 * roles carry in the {@code roles} table; this enum is the compile-time mirror of that catalogue.
 *
 * <h3>The tier, and why it is declared here rather than read from the database</h3>
 * Every role carries a {@link #getTier() tier} — an ordinal from 1 (least privileged) to 7 (most)
 * expressing the escalation ladder:
 *
 * <pre>
 *   GUEST(1) &lt; USER(2) &lt; MODERATOR(3) &lt; HELP_DESK_ADMIN(4)
 *           &lt; ORGANIZATION_ADMIN(5) &lt; ADMIN(6) &lt; APPLICATION_ADMIN(7)
 * </pre>
 *
 * <p>The numbers coincide with the ids {@code schema.sql} pins for these rows, but they are
 * deliberately <em>not read from the database</em>. Those ids are known to have drifted on
 * databases seeded before the pinning landed — {@code INSERT … ON DUPLICATE KEY UPDATE} consumes an
 * AUTO_INCREMENT value for every row it touches, including rows it merely updates, so a database
 * seeded five times shows roles numbered in the 30s. An authorization decision must not depend on
 * how many times somebody ran a seed script, so the ladder is a property of the code.
 *
 * <p>Nor can the tier be {@link Enum#ordinal()}: the constants below are declared in their original
 * historical order, which is not privilege order, and reordering them would silently change the
 * meaning of anything that had persisted an ordinal.
 *
 * <p>This ordering is what {@code AdminUserController} uses to refuse privilege-elevation-by-proxy —
 * an administrator assigning a role above their own tier.
 */
public enum RoleType {
    /**
     * Standard user with basic permissions
     */
    ROLE_USER(2),
    /**
     * Administrator with full system access
     */
    ROLE_ADMIN(6),
    /**
     * Help desk specialist role
     */
    ROLE_HELP_DESK_ADMIN(4),
    /**
     * Guest user with limited permissions
     */
    ROLE_GUEST(1),
    /**
     * Content moderator role
     */
    ROLE_MODERATOR(3),
    /**
     * Organization administrator role
     */
    ROLE_ORGANIZATION_ADMIN(5),
    /**
     * Application administrator role
     */
    ROLE_APPLICATION_ADMIN(7);

    private final int tier;

    RoleType(int tier) {
        this.tier = tier;
    }

    /**
     * This role's position on the privilege ladder — 1 is the least privileged.
     *
     * @return the tier, between 1 and 7 inclusive
     */
    public int getTier() {
        return tier;
    }

    /**
     * Resolves a role name to its constant, case-insensitively and null-safely.
     *
     * <p>Returns an empty {@link Optional} rather than throwing for an unrecognised name, so callers
     * decide what an unknown role means. Every authorization caller must treat it as a denial: a
     * role this enum has never heard of has no place on the ladder, and inventing a tier for it
     * would be guessing at a security boundary.
     *
     * @param roleName the role name to resolve, e.g. {@code ROLE_MODERATOR}; may be null or blank
     * @return the matching constant, or empty if the name is null, blank or unknown
     */
    public static Optional<RoleType> from(String roleName) {
        if (roleName == null || roleName.isBlank()) return Optional.empty();
        String normalized = roleName.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(role -> role.name().equals(normalized))
                .findFirst();
    }

    /**
     * Whether a caller holding {@code callerRole} may assign {@code targetRole}.
     *
     * <p>The rule is "not above your own tier": equal is permitted, so an administrator can create a
     * peer, but nobody can hand out authority they do not themselves hold.
     *
     * <p>Without this, organization scope bounds <em>who</em> an org admin may act on while leaving
     * <em>which role</em> unbounded — so a tier-5 org admin could promote an in-scope user to
     * {@code ROLE_ADMIN}, an unscoped tier-6 account, and then act through it without the scope
     * restriction that applies to themselves. That is privilege elevation by proxy, and it defeats
     * the point of scoping.
     *
     * <p><strong>Fails closed.</strong> If either name is unrecognised, the answer is no.
     *
     * @param callerRole the assigning administrator's role name
     * @param targetRole the role name being assigned
     * @return true only when both roles are recognised and the target does not outrank the caller
     */
    public static boolean canAssign(String callerRole, String targetRole) {
        Optional<RoleType> caller = from(callerRole);
        Optional<RoleType> target = from(targetRole);
        if (caller.isEmpty() || target.isEmpty()) return false;
        return target.get().getTier() <= caller.get().getTier();
    }

    /**
     * Whether a caller holding this role sees only the data of the organizations they actively
     * belong to, rather than every organization's, on the org-aware admin surface
     * ({@code AdminUserController}'s user directory, {@code AnalyticsController}'s rollups).
     *
     * <p>The unscoped tiers are the two highest — {@link #ROLE_ADMIN} and
     * {@link #ROLE_APPLICATION_ADMIN} — everyone else is scoped, keyed off the tier rather than
     * an enumerated list of "the scoped roles." Enumerating scoped roles by name is exactly the
     * shape of bug this method replaces: {@code ROLE_HELP_DESK_ADMIN} also carries
     * {@code UPDATE:USER} and reaches these endpoints, but a check that only recognised
     * {@code ROLE_ORGANIZATION_ADMIN} by name let it through completely unscoped. Keying off
     * "below the unscoped tiers" instead means a future role slotted in anywhere below tier 6
     * is scoped automatically, with nothing new to remember to update here.
     *
     * @return true when this role is below the two unscoped tiers
     */
    public boolean isOrganizationScoped() {
        return this.tier < ROLE_ADMIN.tier;
    }

    /**
     * {@link #isOrganizationScoped()}, resolved from a role name and fail-closed: an
     * unrecognised role is treated as scoped (restricted), never as unscoped (global access) —
     * the same fail-closed direction {@link #canAssign} takes for an unrecognised name.
     *
     * @param roleName the caller's role name; may be null or blank
     * @return true when the role is recognised and below the unscoped tiers, or when it is not
     *         recognised at all
     */
    public static boolean isOrganizationScoped(String roleName) {
        return from(roleName).map(RoleType::isOrganizationScoped).orElse(true);
    }
}
