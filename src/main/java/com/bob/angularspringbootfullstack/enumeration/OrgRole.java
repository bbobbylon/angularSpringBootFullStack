package com.bob.angularspringbootfullstack.enumeration;

import java.util.Arrays;
import java.util.Optional;

/**
 * A user's capacity <em>within one organization</em> — the value carried by
 * {@code userorganizations.org_role}.
 *
 * <h3>How this relates to {@link RoleType}</h3>
 * {@link RoleType} is the <b>global</b> privilege ladder: exactly one role per user
 * ({@code userroles} carries {@code UNIQUE (user_id)}), and it decides which authority strings
 * ({@code READ:USER}, {@code UPDATE:ORGANIZATION}, …) reach the {@code SecurityConfig} matchers.
 * {@code OrgRole} decides nothing about which endpoints a caller may reach; it answers a narrower
 * question the global role cannot express — <em>in what capacity does this user belong to
 * <b>this</b> organization?</em>
 *
 * <p>The two compose rather than compete:
 * <ol>
 *   <li>The global role gates the endpoint. A caller with no {@code UPDATE:ORGANIZATION} authority
 *       never reaches a membership-management handler at all, whatever their {@code OrgRole}.</li>
 *   <li>{@link RoleType#isOrganizationScoped(String)} decides whether org scoping applies. The two
 *       unscoped platform tiers ({@code ROLE_ADMIN}, {@code ROLE_APPLICATION_ADMIN}) bypass every
 *       organization check, exactly as before this enum existed.</li>
 *   <li>For everyone else, {@code OrgRole} decides <em>which</em> organizations they may act on.</li>
 * </ol>
 *
 * <p>Before this existed, step 3 had no answer: "organization admin" was the global
 * {@code ROLE_ORGANIZATION_ADMIN} tier, whose scope was "every organization I actively belong to".
 * A user could not be an administrator of one organization and an ordinary member of another, which
 * is the distinction genuine multi-tenancy is built on.
 *
 * <h3>The tier, and why it is declared here</h3>
 * <pre>
 *   ORG_VIEWER(1) &lt; ORG_MEMBER(2) &lt; ORG_ADMIN(3)
 * </pre>
 *
 * <p>Declared in code rather than read from the database, for the same reason {@link RoleType}
 * pins its tiers rather than reading {@code roles.id}: an authorization decision must not depend on
 * what a seed script happened to write, or on how many times somebody ran it. Nor can the tier be
 * {@link Enum#ordinal()} — reordering the constants below would silently change the meaning of
 * anything that had persisted an ordinal.
 *
 * @see RoleType the global privilege ladder this composes with
 */
public enum OrgRole {
    /**
     * Read-only within the organization. Cannot manage membership or organization settings.
     */
    ORG_VIEWER(1),
    /**
     * Ordinary member — the default any new membership row takes, and the column default in
     * {@code schema.sql}.
     */
    ORG_MEMBER(2),
    /**
     * Administers this one organization: may manage its membership and read its audit trail,
     * without any elevation in organizations they do not administer.
     */
    ORG_ADMIN(3);

    /**
     * The default capacity for a membership created without an explicit role — kept here rather
     * than at each call site so the Java default and {@code schema.sql}'s column default cannot
     * drift apart.
     */
    public static final OrgRole DEFAULT = ORG_MEMBER;

    private final int tier;

    OrgRole(int tier) {
        this.tier = tier;
    }

    /**
     * This role's position on the in-organization ladder — 1 is the least privileged.
     *
     * @return the tier, between 1 and 3 inclusive
     */
    public int getTier() {
        return tier;
    }

    /**
     * Resolves an org-role name to its constant, case-insensitively and null-safely.
     *
     * <p>Returns an empty {@link Optional} rather than throwing for an unrecognized name, so
     * callers decide what an unknown role means. Every authorization caller must treat it as a
     * denial — a role this enum has never heard of has no place on the ladder, and inventing a
     * tier for it would be guessing at a security boundary. This mirrors
     * {@link RoleType#from(String)} exactly.
     *
     * @param orgRoleName the org-role name to resolve, e.g. {@code ORG_ADMIN}; may be null or blank
     * @return the matching constant, or empty if the name is null, blank or unknown
     */
    public static Optional<OrgRole> from(String orgRoleName) {
        if (orgRoleName == null || orgRoleName.isBlank()) return Optional.empty();
        String normalized = orgRoleName.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(orgRole -> orgRole.name().equals(normalized))
                .findFirst();
    }

    /**
     * Whether this role is at least as privileged as {@code required}.
     *
     * @param required the minimum capacity being demanded
     * @return true when this role's tier is greater than or equal to {@code required}'s
     */
    public boolean isAtLeast(OrgRole required) {
        return this.tier >= required.tier;
    }

    /**
     * Whether a membership carrying {@code orgRoleName} satisfies a demand for {@code required},
     * fail-closed: an unrecognized, blank or null name is <em>not</em> sufficient for anything.
     *
     * <p>This is the form authorization call sites want, because the value arriving from the
     * database is a {@code String} and the safe reading of "we do not recognize this" is denial —
     * the same direction {@link RoleType#canAssign} and
     * {@link RoleType#isOrganizationScoped(String)} take.
     *
     * @param orgRoleName the membership row's stored org role; may be null or blank
     * @param required    the minimum capacity being demanded
     * @return true only when the name is recognized and its tier meets or exceeds {@code required}
     */
    public static boolean satisfies(String orgRoleName, OrgRole required) {
        return from(orgRoleName).map(orgRole -> orgRole.isAtLeast(required)).orElse(false);
    }

    /**
     * Whether a caller holding {@code callerOrgRole} in an organization may assign
     * {@code targetOrgRole} within that same organization.
     *
     * <p>The rule is "not above your own tier" — equal is permitted, so an {@code ORG_ADMIN} can
     * appoint a peer administrator, but nobody hands out authority they do not themselves hold.
     * This is the in-organization mirror of {@link RoleType#canAssign}, and it exists for the same
     * reason: without it, scope bounds <em>who</em> an administrator may act on while leaving
     * <em>which capacity</em> unbounded.
     *
     * <p><strong>Fails closed.</strong> If either name is unrecognized, the answer is no.
     *
     * @param callerOrgRole the assigning member's org role name
     * @param targetOrgRole the org role name being assigned
     * @return true only when both are recognized and the target does not outrank the caller
     */
    public static boolean canAssign(String callerOrgRole, String targetOrgRole) {
        Optional<OrgRole> caller = from(callerOrgRole);
        Optional<OrgRole> target = from(targetOrgRole);
        if (caller.isEmpty() || target.isEmpty()) return false;
        return target.get().getTier() <= caller.get().getTier();
    }

    /**
     * The org role an invite should grant on redemption, derived from the <em>global</em> role name
     * the invite was created with.
     *
     * <p>Invites predate this enum and store a {@link RoleType} name in
     * {@code organizationinvites.role_name}. Rather than migrate that column and every outstanding
     * invite, redemption maps it: a global tier that meant "can administer organizations"
     * ({@code ROLE_ORGANIZATION_ADMIN} and above) becomes {@link #ORG_ADMIN} <em>of the inviting
     * organization only</em>; anything else becomes an ordinary {@link #ORG_MEMBER}.
     *
     * <p>This is what closes the cross-tenant escalation the invite flow used to carry. The old
     * behaviour granted the stored role <b>globally</b>, so redeeming one organization's invite
     * elevated the redeemer in every organization they belonged to.
     *
     * <p>Fails closed: an unrecognized name yields {@link #ORG_MEMBER}, never {@code ORG_ADMIN}.
     *
     * @param globalRoleName the invite's stored {@code role_name}; may be null or blank
     * @return the org role to write onto the membership row
     */
    public static OrgRole fromInvitedGlobalRole(String globalRoleName) {
        return RoleType.from(globalRoleName)
                .filter(roleType -> roleType.getTier() >= RoleType.ROLE_ORGANIZATION_ADMIN.getTier())
                .map(roleType -> ORG_ADMIN)
                .orElse(ORG_MEMBER);
    }
}
