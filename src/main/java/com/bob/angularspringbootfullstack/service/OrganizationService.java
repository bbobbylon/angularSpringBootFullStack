package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.model.OrganizationEvent;
import com.bob.angularspringbootfullstack.model.OrganizationInvite;
import com.bob.angularspringbootfullstack.model.OrganizationStats;
import com.bob.angularspringbootfullstack.model.OrganizationSummary;

import com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod;
import com.bob.angularspringbootfullstack.enumeration.OrgRole;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Business contract for organization-scoped authorization (SRS §4.6 FR-ORG-1..3).
 *
 * <p>Organizations bound what {@code ROLE_ORGANIZATION_ADMIN} can see and touch: such an
 * administrator may act only on users who share at least one ACTIVE organization
 * membership with them. {@code AdminUserController} consults this service before every
 * administrative read or mutation when the caller is org-scoped;
 * {@code ROLE_APPLICATION_ADMIN} and {@code ROLE_ADMIN} bypass it entirely (FR-ORG-3
 * names only the application admin, and the SRS scopes only the organization admin —
 * the global admin tier is unscoped by definition).
 */
public interface OrganizationService {

    /**
     * Whether the target user shares at least one active organization with the
     * administrator — the FR-ORG-2 scope predicate. A {@code false} answer must result
     * in HTTP 403 for any administrative action on the target.
     *
     * @param adminId  the acting administrator's user id
     * @param targetId the user id the administrator wants to act on
     * @return true when both users hold active memberships in a common organization
     */
    boolean isWithinOrganizationScope(Long adminId, Long targetId);

    /**
     * Returns the ids of every organization the given user actively belongs to.
     *
     * <p>This is the tenant filter for org-scoped <em>aggregates</em> (FR-ORG-2), the complement to
     * {@link #isWithinOrganizationScope}: that method answers a yes/no question about one target,
     * which fits per-user administrative actions, while reporting needs the set of organizations up
     * front so the restriction can be pushed into the aggregating SQL. A total cannot be filtered
     * after it has been summed — the attribution is gone by then — so the scope has to be known
     * before the query runs.
     *
     * <p>An empty result means the caller belongs to no active organization and must therefore see
     * <em>nothing</em>. Callers must not fall back to unscoped data on empty; that would invert the
     * control and hand a membership-less admin the system-wide view.
     *
     * @param userId the administrator whose memberships bound the query
     * @return the active organization ids, possibly empty, never {@code null}
     */
    Collection<Long> findActiveOrganizationIds(Long userId);

    /**
     * The organization-scoped user directory: pages through only the users sharing an
     * active organization with the administrator, with the same free-text filter and
     * DTO enrichment as the unscoped {@link UserService#searchUsers} so the admin
     * dashboard renders identically for both admin tiers.
     *
     * @param adminId    the acting administrator's user id
     * @param searchTerm free-text filter; blank or null lists everyone in scope
     * @param page       0-indexed page number
     * @param pageSize   rows per page
     * @param orderBy    a validated, {@code u.}-qualified {@code "column ASC|DESC"} SQL fragment
     *                   (see {@code SortUtils#resolveSqlOrderBy}), e.g. {@code "u.created_at DESC, u.id DESC"} —
     *                   qualified because the query joins {@code userorganizations}, so an unqualified
     *                   column name could collide with one on the joined table
     * @return the in-scope users on the requested page, in the requested order
     */
    Collection<UserDTO> searchUsersSharingOrganizations(Long adminId, String searchTerm, int page, int pageSize, String orderBy);

    /**
     * Counts the users {@link #searchUsersSharingOrganizations} would match, for
     * total-pages metadata.
     *
     * @param adminId    the acting administrator's user id
     * @param searchTerm free-text filter; blank or null counts everyone in scope
     * @return the total number of in-scope matching users
     */
    long countUsersSharingOrganizations(Long adminId, String searchTerm);

    /**
     * Lists every active organization's id and name — the iteration set the scheduled report
     * digest walks to send each organization its own org-scoped digest
     * (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails").
     *
     * @return every active organization, ordered by name
     */
    Collection<OrganizationSummary> findActiveOrganizations();

    /**
     * The email addresses of every {@code ROLE_ORGANIZATION_ADMIN} holding an active membership
     * in the given organization — the recipient list for that organization's report digest.
     *
     * @param organizationId the organization whose admins should receive the digest
     * @return the in-scope organization admins' emails, possibly empty, never {@code null}
     */
    Collection<String> findOrganizationAdminEmails(Long organizationId);

    // ── Organization CRUD + membership management (2026-08-21, FUTURE-ENHANCEMENTS.md §3.2) ──
    // Gated to unscoped tiers (catalog mutation) and to an active member of the target org
    // (membership mutation) at OrganizationController — these methods are pure data access with
    // no role-awareness of their own, the same separation RoleService/RoleController keep for
    // Role CRUD.

    /**
     * Creates a new organization, always starting {@code ACTIVE}, in one write covering its full
     * initial setup — profile, tenant UUID, and MFA/feature-flag settings — rather than a
     * create-then-several-PATCHes dance. Attaching existing customers and sending a creation
     * confirmation email are orchestrated by {@code OrganizationController} after this call
     * returns, not performed here: they are cross-cutting concerns ({@code CustomerService},
     * {@code EmailService}) this method has no need to depend on.
     *
     * @param name              the organization's display name; must be non-blank and not already taken
     * @param description       free-form description, or {@code null}
     * @param contactEmail      organization contact email, or {@code null}
     * @param website           organization website, or {@code null}
     * @param tenantUuid        the admin-supplied external tenant identifier, or {@code null};
     *                          must be a valid UUID string and not already in use if supplied
     * @param mfaAllowedMethods the MFA methods this organization's members may enroll in, or
     *                          {@code null}/empty for "no policy configured" (every method allowed)
     * @param featureFlags      free-form feature-flag labels, or {@code null}/empty
     * @return the created organization with its generated id populated
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if the name is blank or
     *         already taken, or {@code tenantUuid} is malformed or already in use
     */
    Organization createOrganization(String name, String description, String contactEmail, String website,
                                     String tenantUuid, Set<OrgMfaMethod> mfaAllowedMethods, List<String> featureFlags);

    /**
     * Creates a new organization with just a name — the common case, and the shape every
     * pre-org-setup caller used.
     *
     * @param name the organization's display name; must be non-blank and not already taken
     * @return the created organization with its generated id populated
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if the name is blank or
     *         already taken
     */
    default Organization createOrganization(String name) {
        return createOrganization(name, null, null, null, null, null, null);
    }

    /**
     * Returns every organization the caller may see: the full catalog for an unscoped tier, or
     * only the organizations in {@code organizationIds} otherwise.
     *
     * @param organizationIds {@code null} for the unscoped (full-catalog) view; otherwise the
     *                        exact set of organization ids to return — an empty collection
     *                        correctly yields an empty result, never falling back to the full
     *                        catalog
     * @return the in-scope organizations, ordered by id
     */
    Collection<Organization> listOrganizations(Collection<Long> organizationIds);

    /**
     * Renames an organization.
     *
     * @param id   the organization to rename
     * @param name the new display name; must be non-blank and not already taken by another
     *             organization
     * @return the renamed organization, freshly re-read from the database
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no organization has
     *         that id, the name is blank, or the name is already taken
     */
    Organization renameOrganization(Long id, String name);

    /**
     * Activates or deactivates an organization — the retirement lever; see
     * {@link Organization}'s Javadoc for why this, not a hard delete, is how an organization is
     * retired.
     *
     * @param id     the organization to update
     * @param status {@code "ACTIVE"} or {@code "INACTIVE"}
     * @return the updated organization, freshly re-read from the database
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no organization has
     *         that id, or {@code status} is neither {@code ACTIVE} nor {@code INACTIVE}
     */
    Organization setOrganizationStatus(Long id, String status);

    /**
     * Whether {@code userId} holds an active membership in {@code organizationId} — the
     * predicate {@code OrganizationController} uses to let a {@code ROLE_ORGANIZATION_ADMIN}
     * manage membership of their own organization only.
     *
     * @param userId         the user whose membership is being checked
     * @param organizationId the organization to check membership in
     * @return true when the user holds an active membership in that organization
     */
    boolean isActiveMemberOfOrganization(Long userId, Long organizationId);

    /**
     * Adds a user to an organization with a given capacity, or reactivates their membership if
     * they previously belonged and were removed.
     *
     * <p>The role is re-asserted on reactivation rather than inherited: a member removed as an
     * {@code ORG_ADMIN} and later re-added comes back as whatever this call says, so re-adding
     * somebody is never a silent re-grant of the authority they used to hold.
     *
     * @param organizationId the organization to add the user to
     * @param userId         the user to add
     * @param orgRole        the capacity to grant within this organization
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if either id does not
     *         exist
     */
    void addMember(Long organizationId, Long userId, OrgRole orgRole);

    /**
     * Adds a user to an organization as an ordinary {@link OrgRole#DEFAULT} member — the common
     * case, and the shape every pre-{@code org_role} caller used.
     *
     * @param organizationId the organization to add the user to
     * @param userId         the user to add
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if either id does not
     *         exist
     */
    default void addMember(Long organizationId, Long userId) {
        addMember(organizationId, userId, OrgRole.DEFAULT);
    }

    /**
     * Auto-joins a user to an organization on the strength of a successful external IdP login
     * (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 2) — the org's own SSO
     * configuration is the vouching mechanism, in place of an admin-issued invite.
     *
     * <p><b>Checks membership first rather than calling {@link #addMember} unconditionally.</b>
     * {@code addMember}'s reactivation path re-asserts {@code orgRole} even for an already-active
     * member (see its Javadoc above) — calling it on every single SSO login would silently demote a
     * returning {@link OrgRole#ORG_ADMIN} back to {@link OrgRole#DEFAULT} each time they signed in.
     * This method exists specifically so {@code OAuth2LoginSuccessHandler} never needs to make that
     * mistake: an already-active member is left alone entirely.
     *
     * @param organizationId the organization the login was authenticated against
     * @param userId         the local user id the login resolved to
     * @return {@code true} if this call just created a brand-new membership, {@code false} if the
     * user was already an active member (a no-op)
     */
    default boolean ensureAutoJoinMembership(Long organizationId, Long userId) {
        if (isActiveMemberOfOrganization(userId, organizationId)) {
            return false;
        }
        addMember(organizationId, userId, OrgRole.DEFAULT);
        return true;
    }

    /**
     * Removes a user from an organization by deactivating their membership row — see
     * {@link Organization}'s Javadoc for why membership removal is a soft flag, not a delete.
     *
     * @param organizationId the organization to remove the user from
     * @param userId         the user to remove
     */
    void removeMember(Long organizationId, Long userId);

    /**
     * Lists every user holding an ACTIVE membership in one organization — the read side the admin
     * UI needs to show who is in an organization before adding or removing anyone, mirroring
     * {@link #searchUsersSharingOrganizations} enrichment shape but unpaginated: one organization's
     * roster is bounded by headcount, not by the whole directory.
     *
     * @param organizationId the organization whose active members should be listed
     * @return the in-scope members, ordered by name, possibly empty, never {@code null}
     */
    Collection<UserDTO> listActiveMembers(Long organizationId);

    // ── Organization profile/settings, audit trail, invites, stats (2026-08-22 dashboard revamp) ──

    /**
     * Updates an organization's profile fields. All three are independently nullable — clearing
     * one does not require resending the others as empty strings versus {@code null}; the caller
     * passes exactly what the form submitted.
     *
     * @param id           the organization to update
     * @param description  free-form description, or {@code null} to clear it
     * @param contactEmail organization contact email, or {@code null} to clear it
     * @param website      organization website, or {@code null} to clear it
     * @return the updated organization, freshly re-read from the database
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no organization has
     *         that id
     */
    Organization updateOrganizationProfile(Long id, String description, String contactEmail, String website);

    /**
     * Records an organization audit entry — the write side of the organization-level activity
     * log, called by {@link com.bob.angularspringbootfullstack.listener.NewOrganizationEventListener}
     * after a {@code NewOrganizationEvent} is published. Not called directly by controllers, the
     * same separation {@code EventService#addUserEvent} keeps from {@code NewUserEventListener}.
     *
     * @param organizationId the organization the event occurred on
     * @param actorUserId    the acting administrator's user id, or {@code null}
     * @param eventType      the category of action that occurred
     * @param detail         optional free-form context; may be {@code null}
     */
    void recordOrganizationEvent(Long organizationId, Long actorUserId, EventType eventType, String detail);

    /**
     * Returns one page of an organization's audit trail, newest first.
     *
     * @param organizationId the organization whose activity to retrieve
     * @param page           zero-based page index
     * @param size           maximum number of entries per page
     * @return the page-sized collection of resolved {@link OrganizationEvent} rows
     */
    Collection<OrganizationEvent> listOrganizationEvents(Long organizationId, int page, int size);

    /**
     * Counts an organization's audit entries, for total-pages metadata.
     *
     * @param organizationId the organization whose activity to count
     * @return the total number of audit entries
     */
    long countOrganizationEvents(Long organizationId);

    /**
     * Creates a pending, single-use invite for an organization.
     *
     * @param organizationId  the organization the invite joins its redeemer to
     * @param invitedByUserId the creating administrator's user id
     * @param roleName        the role granted on redemption
     * @param ttlHours        how many hours the invite remains redeemable
     * @return the created invite, including its redeemable {@code code}
     */
    OrganizationInvite createInvite(Long organizationId, Long invitedByUserId, String roleName, long ttlHours);

    /**
     * Lists an organization's outstanding (not-yet-expired) invites, newest first.
     *
     * @param organizationId the organization whose invites to list
     * @return the organization's active invites, possibly empty, never {@code null}
     */
    Collection<OrganizationInvite> listActiveInvites(Long organizationId);

    /**
     * Revokes an outstanding invite before it is redeemed.
     *
     * @param organizationId the organization the invite belongs to — scopes the delete so a
     *                       caller cannot revoke another organization's invite by guessing its id
     * @param inviteId       the invite to revoke
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no matching invite
     *         exists in that organization
     */
    void revokeInvite(Long organizationId, Long inviteId);

    /**
     * Resolves an invite's organization name for the join page's confirmation prompt
     * ("Join {name}?"), without redeeming it. Returns empty for an unknown or expired code — the
     * same "not found" verdict {@link #redeemInvite} gives, so a stale link cannot be used to
     * fingerprint whether it once existed (NFR-SEC-7).
     *
     * @param code the invite code from the join link
     * @return the organization's name, or empty if the code is unknown or expired
     */
    Optional<String> previewInvite(String code);

    /**
     * Redeems an invite: adds {@code userId} as a member of the invite's organization with the
     * invite's granted role, then deletes the invite row so it cannot be redeemed again.
     *
     * <p>Fails with {@link com.bob.angularspringbootfullstack.exception.ApiException} for an
     * unknown, already-redeemed, or expired code — deliberately the same message for all three, so
     * the response cannot be used to distinguish "this never existed" from "someone already used
     * it" (NFR-SEC-7).
     *
     * @param code   the invite code from the join link
     * @param userId the redeeming (already-authenticated) user's id
     * @return the organization the user just joined
     */
    Organization redeemInvite(String code, Long userId);

    // ── Per-organization roles (2026-08-26, TODO(org-roles)) ────────────────────────────────

    /**
     * The capacity {@code userId} holds within {@code organizationId}, or empty when they hold no
     * ACTIVE membership there.
     *
     * <p>Empty is the "not a member here" answer and every authorization caller must read it as a
     * denial — it is deliberately not conflated with {@link OrgRole#ORG_VIEWER}, which is a real,
     * granted capacity.
     *
     * @param userId         the member whose capacity is being read
     * @param organizationId the organization to read it in
     * @return the member's org role, or empty when there is no active membership
     */
    Optional<OrgRole> findOrgRole(Long userId, Long organizationId);

    /**
     * Every organization the user actively belongs to, mapped to the capacity they hold in each —
     * the {@code getOrgRolesForUser} projection, for surfaces that need capacity across all of a
     * user's organizations without one query per organization.
     *
     * @param userId the user whose memberships to resolve
     * @return organization id → org role, possibly empty, never {@code null}
     */
    Map<Long, OrgRole> findOrgRoles(Long userId);

    /**
     * Whether the user administers this one organization — {@link #findOrgRole} narrowed to the
     * {@link OrgRole#ORG_ADMIN} question that membership management actually asks.
     *
     * <p>Fails closed: no membership, an unrecognized stored role, or a read error all answer
     * {@code false}, the same direction {@link #isWithinOrganizationScope} takes.
     *
     * @param userId         the member to test
     * @param organizationId the organization to test them in
     * @return true only when the user holds an active {@code ORG_ADMIN} membership there
     */
    boolean isOrgAdminOf(Long userId, Long organizationId);

    /**
     * Reassigns one active member's capacity within one organization.
     *
     * <p>Refuses to demote the organization's last remaining administrator — an organization with
     * no {@code ORG_ADMIN} can only be repaired by an unscoped platform tier, so the demotion is
     * rejected rather than silently stranding it.
     *
     * @param organizationId the organization the membership belongs to
     * @param userId         the member being reassigned
     * @param orgRole        the capacity to grant
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if the user holds no active
     *         membership in that organization, or the change would remove its last administrator
     */
    void setMemberOrgRole(Long organizationId, Long userId, OrgRole orgRole);

    /**
     * Whether {@code organizationId} would still have at least one active {@code ORG_ADMIN} if
     * {@code excludedUserId} were demoted or removed — the guard behind
     * {@link #setMemberOrgRole} and {@link #removeMember}.
     *
     * @param organizationId the organization to check
     * @param excludedUserId the member being demoted or removed, excluded from the count
     * @return true when another active administrator remains
     */
    boolean hasOtherActiveOrgAdmin(Long organizationId, Long excludedUserId);

    /**
     * Every active member's capacity within one organization — the Members tab's per-row role
     * selector needs this alongside {@link #listActiveMembers}, since a {@code UserDTO} carries
     * only the member's <em>global</em> role. Kept as its own lookup rather than folded into
     * {@code UserDTO} itself: {@code org_role} is meaningful only in the context of one
     * organization, and a user can hold a different capacity in each one they belong to.
     *
     * @param organizationId the organization whose members' capacities to resolve
     * @return user id → org role, for every active membership with a recognized stored role;
     *         possibly empty, never {@code null}
     */
    Map<Long, OrgRole> orgRolesForOrganization(Long organizationId);

    /**
     * The per-organization KPI row for the dashboard-style Organizations page: member count plus
     * this organization's customer/invoice/revenue rollups, delegating to
     * {@link com.bob.angularspringbootfullstack.service.CustomerService}'s existing
     * {@code *ForOrganizations} methods narrowed to one id — not a new aggregation.
     *
     * @param organizationId the organization to summarize
     * @return the organization's stat tiles
     */
    OrganizationStats getOrganizationStats(Long organizationId);

    // ── Org setup: tenant UUID, MFA policy, feature flags (2026-08-28) ─────────────────────

    /**
     * Sets an organization's external tenant UUID — exactly once. Refuses the write outright if
     * the organization already has one set; there is no "change" operation, matching the
     * "admin-settable, settable once" requirement.
     *
     * @param id         the organization to set the tenant UUID on
     * @param tenantUuid the UUID to set; must be a valid UUID string
     * @return the updated organization, freshly re-read from the database
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no organization has
     *         that id, the organization already has a tenant UUID, the value is not a valid UUID,
     *         or it is already in use by another organization
     */
    Organization setTenantUuid(Long id, String tenantUuid);

    /**
     * Updates an organization's enforcement-relevant settings. Both fields are independently
     * nullable, mirroring {@link #updateOrganizationProfile}: {@code null} leaves that setting
     * unchanged, an empty collection clears it.
     *
     * @param id                the organization to update
     * @param mfaAllowedMethods the new MFA policy, {@code null} to leave unchanged, or empty to
     *                          clear it back to "no policy configured"
     * @param featureFlags      the new feature-flag labels, {@code null} to leave unchanged, or
     *                          empty to clear them
     * @return the updated organization, freshly re-read from the database
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no organization has
     *         that id
     */
    Organization updateOrganizationSettings(Long id, Set<OrgMfaMethod> mfaAllowedMethods, List<String> featureFlags);

    /**
     * Whether {@code userId} may enroll in {@code method}, resolved across every organization they
     * actively belong to.
     *
     * <p><b>Most-restrictive-wins:</b> the method is allowed unless at least one of the user's
     * active organizations has <em>configured</em> a policy that excludes it. An organization with
     * no configured policy imposes no restriction — it is not read as "allows nothing" — and a user
     * with no active organization membership at all is unrestricted. This mirrors this codebase's
     * general fail-closed posture ({@link #isWithinOrganizationScope},
     * {@link #isActiveMemberOfOrganization}) while not retroactively restricting every organization
     * that has never touched the setting.
     *
     * @param userId the user attempting to enroll
     * @param method the method they are attempting to enroll in
     * @return true when no active organization's configured policy excludes this method
     */
    boolean isMfaMethodAllowed(Long userId, OrgMfaMethod method);
}
