package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.OrgRole;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.form.OrganizationForm;
import com.bob.angularspringbootfullstack.form.OrganizationInviteForm;
import com.bob.angularspringbootfullstack.form.OrganizationProfileForm;
import com.bob.angularspringbootfullstack.form.OrganizationStatusForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.model.OrganizationInvite;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_CREATED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_INVITE_CREATED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_INVITE_REVOKED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_MEMBER_ADDED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_MEMBER_REMOVED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_MEMBER_ROLE_CHANGED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_PROFILE_UPDATED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_RENAMED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_STATUS_CHANGED;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Self-service organization management (Organization CRUD + membership management —
 * FUTURE-ENHANCEMENTS.md §3.2).
 * <p>
 * Two distinct authorization rules apply on this controller, decided per-endpoint rather than
 * uniformly, because the two operation families have different blast radii:
 * <ul>
 *   <li><b>Catalog mutation</b> (create/rename/status) changes what an organization <em>is</em>
 *       for everyone scoped to it — refused below the unscoped tiers ({@code ROLE_ADMIN},
 *       {@code ROLE_APPLICATION_ADMIN}), checked by {@link #requireUnscopedTier}, mirroring
 *       {@link RoleController#requireApplicationAdmin}'s shape for the analogous Role CRUD
 *       decision.</li>
 *   <li><b>Membership mutation</b> (add/remove a member, change a member's org role) only
 *       changes who belongs to <em>one</em> organization, so a scoped caller may perform it for an
 *       organization they administer — checked by {@link #requireMembershipAuthority}, which
 *       consults {@link OrganizationService#isOrgAdminOf}. An unscoped tier may manage any
 *       organization's membership.</li>
 * </ul>
 * <p>
 * <b>Per-organization roles (2026-08-26).</b> The membership rule above is keyed on
 * {@code userorganizations.org_role} — the caller's capacity in <em>that one</em> organization —
 * not on their global {@link RoleType}. Before this, "organization admin" was the global
 * {@code ROLE_ORGANIZATION_ADMIN} tier and its reach was every organization the holder belonged to,
 * so there was no way to be an administrator of one organization and an ordinary member of another.
 * The global role still gates the endpoint (via {@code UPDATE:ORGANIZATION}) and still decides
 * whether org scoping applies at all ({@link RoleType#isOrganizationScoped}); the org role decides
 * <em>which</em> organizations a scoped caller may act on. See
 * {@link com.bob.angularspringbootfullstack.enumeration.OrgRole} for how the two compose.
 * <p>
 * Authorization is enforced at two levels, per FR-RBAC-2, matching {@link RoleController}'s
 * convention:
 * <ul>
 *   <li><b>URL level</b> — {@code SecurityConfig} requires {@code UPDATE:ORGANIZATION} for
 *       {@code /admin/organization/**}.</li>
 *   <li><b>Method level</b> — {@link PreAuthorize} repeats that requirement, and the two private
 *       helpers above narrow it further per operation family — narrowing
 *       {@code @PreAuthorize} alone cannot express against this application's authority-string
 *       model.</li>
 * </ul>
 * <p>
 * There is no delete endpoint: an organization is retired via {@link #setStatus}, never
 * removed — see {@link Organization}'s Javadoc for why a hard delete would orphan data.
 */
@RestController
@RequestMapping(path = "/admin/organization")
@RequiredArgsConstructor
@Slf4j
public class OrganizationController {

    private final OrganizationService organizationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Lists the organizations the caller may see: the full catalog for an unscoped tier, or
     * only the organizations they actively belong to otherwise — the same {@code resolveScope}
     * shape {@link CustomerController}, {@link AnalyticsController} and
     * {@link SecurityDashboardController} already apply to their own surfaces.
     *
     * @param authentication the calling administrator's authentication
     * @return 200 OK with the in-scope organizations
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @GetMapping
    public ResponseEntity<HttpResponse> getOrganizations(Authentication authentication) {
        UserDTO caller = getAuthenticatedUser(authentication);
        Collection<Long> scope = resolveScope(caller);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("organizations", organizationService.listOrganizations(scope)))
                        .message("Organizations retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a new organization (Organization CRUD — create), unscoped tiers only.
     *
     * @param authentication the calling administrator's authentication
     * @param form           the validated {name} payload
     * @return 200 OK with the created organization and the refreshed catalog
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PostMapping
    public ResponseEntity<HttpResponse> createOrganization(Authentication authentication, @RequestBody @Valid OrganizationForm form) {
        requireUnscopedTier(authentication);
        Organization created = organizationService.createOrganization(form.getName());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(created.getId(), caller.getId(), ORG_CREATED, created.getName()));
        log.info("'{}' created organization '{}'", caller.getEmail(), created.getName());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("organization", created, "organizations", organizationService.listOrganizations(null)))
                        .message("Organization created successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Renames an organization (Organization CRUD — edit), unscoped tiers only.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the id of the organization to rename
     * @param form           the validated {name} payload
     * @return 200 OK with the renamed organization and the refreshed catalog
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PatchMapping("/{id}/name")
    public ResponseEntity<HttpResponse> renameOrganization(Authentication authentication,
                                                            @PathVariable Long id,
                                                            @RequestBody @Valid OrganizationForm form) {
        requireUnscopedTier(authentication);
        Organization updated = organizationService.renameOrganization(id, form.getName());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(id, caller.getId(), ORG_RENAMED, updated.getName()));
        log.info("'{}' renamed organization id {} to '{}'", caller.getEmail(), id, updated.getName());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("organization", updated, "organizations", organizationService.listOrganizations(null)))
                        .message("Organization renamed successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Activates or deactivates an organization (Organization CRUD — the retirement lever),
     * unscoped tiers only.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the id of the organization to update
     * @param form           the validated {status} payload
     * @return 200 OK with the updated organization and the refreshed catalog
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<HttpResponse> setStatus(Authentication authentication,
                                                   @PathVariable Long id,
                                                   @RequestBody @Valid OrganizationStatusForm form) {
        requireUnscopedTier(authentication);
        Organization updated = organizationService.setOrganizationStatus(id, form.getStatus());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(id, caller.getId(), ORG_STATUS_CHANGED, updated.getStatus()));
        log.info("'{}' set organization id {} status to '{}'", caller.getEmail(), id, updated.getStatus());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("organization", updated, "organizations", organizationService.listOrganizations(null)))
                        .message("Organization status updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Adds (or reactivates) a member of an organization — an unscoped tier may do this for any
     * organization; a scoped caller only for one they administer.
     *
     * <p>{@code orgRole} is optional and defaults to {@link OrgRole#DEFAULT}. When supplied it is
     * bounded by {@link #requireAssignableOrgRole}, so a scoped caller cannot add somebody at a
     * capacity above their own.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization to add the member to
     * @param userId         the user to add
     * @param orgRole        the capacity to grant, or null for {@code ORG_MEMBER}
     * @return 200 OK
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PostMapping("/{organizationId}/members/{userId}")
    public ResponseEntity<HttpResponse> addMember(Authentication authentication,
                                                   @PathVariable Long organizationId,
                                                   @PathVariable Long userId,
                                                   @RequestParam(required = false) String orgRole) {
        requireMembershipAuthority(authentication, organizationId);
        OrgRole granted = requireAssignableOrgRole(authentication, organizationId, orgRole);
        organizationService.addMember(organizationId, userId, granted);
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_MEMBER_ADDED, "user " + userId));
        log.info("'{}' added user {} to organization {}", caller.getEmail(), userId, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Member added successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Removes a member from an organization — same authorization rule as {@link #addMember}.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization to remove the member from
     * @param userId         the user to remove
     * @return 200 OK
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @DeleteMapping("/{organizationId}/members/{userId}")
    public ResponseEntity<HttpResponse> removeMember(Authentication authentication,
                                                      @PathVariable Long organizationId,
                                                      @PathVariable Long userId) {
        requireMembershipAuthority(authentication, organizationId);
        organizationService.removeMember(organizationId, userId);
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_MEMBER_REMOVED, "user " + userId));
        log.info("'{}' removed user {} from organization {}", caller.getEmail(), userId, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Member removed successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Changes an existing member's capacity within one organization — the promote/demote lever
     * that makes per-organization roles usable, and the path by which an organization gains its
     * first {@code ORG_ADMIN} after an unscoped tier creates it and adds people.
     *
     * <p>Same authorization rule as {@link #addMember}, plus the assignment ceiling in
     * {@link #requireAssignableOrgRole}. The service additionally refuses to demote an
     * organization's last remaining administrator.
     *
     * <p>Audited as {@code ORG_MEMBER_ROLE_CHANGED}, the event type the catalog already carried for
     * the global-role reassignment hint on {@code AdminUserController#updateUserRole} — this is the
     * organization-scoped counterpart of that action, so it shares the type rather than inventing
     * a near-duplicate one.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization the membership belongs to
     * @param userId         the member being reassigned
     * @param orgRole        the capacity to grant — {@code ORG_ADMIN}, {@code ORG_MEMBER} or
     *                       {@code ORG_VIEWER}
     * @return 200 OK
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PatchMapping("/{organizationId}/members/{userId}/role")
    public ResponseEntity<HttpResponse> setMemberOrgRole(Authentication authentication,
                                                         @PathVariable Long organizationId,
                                                         @PathVariable Long userId,
                                                         @RequestParam String orgRole) {
        requireMembershipAuthority(authentication, organizationId);
        OrgRole granted = requireAssignableOrgRole(authentication, organizationId, orgRole);
        organizationService.setMemberOrgRole(organizationId, userId, granted);
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_MEMBER_ROLE_CHANGED,
                "user " + userId + " → " + granted.name()));
        log.info("'{}' set user {}'s role in organization {} to {}", caller.getEmail(), userId, organizationId, granted);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Member role updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Lists the active members of one organization — the read side {@link #addMember} and
     * {@link #removeMember} needed: an admin cannot pick a member to remove from a roster they
     * can never see. Same authorization rule as those two, via {@link #requireMembershipAuthority}.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization whose members to list
     * @return 200 OK with the organization's active members
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @GetMapping("/{organizationId}/members")
    public ResponseEntity<HttpResponse> getMembers(Authentication authentication, @PathVariable Long organizationId) {
        requireMembershipAuthority(authentication, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("members", organizationService.listActiveMembers(organizationId)))
                        .message("Members retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates an organization's profile fields (description/contact email/website), unscoped
     * tiers only — the same catalog-mutation rule {@link #renameOrganization} applies, since a
     * profile is as much a catalog attribute as the name.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the organization to update
     * @param form           the validated profile payload; each field independently nullable
     * @return 200 OK with the updated organization
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PatchMapping("/{id}/profile")
    public ResponseEntity<HttpResponse> updateProfile(Authentication authentication,
                                                        @PathVariable Long id,
                                                        @RequestBody @Valid OrganizationProfileForm form) {
        requireUnscopedTier(authentication);
        Organization updated = organizationService.updateOrganizationProfile(id, form.getDescription(), form.getContactEmail(), form.getWebsite());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(id, caller.getId(), ORG_PROFILE_UPDATED, null));
        log.info("'{}' updated the profile of organization id {}", caller.getEmail(), id);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("organization", updated))
                        .message("Organization profile updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns one page of an organization's audit trail, newest first — same authorization rule
     * as {@link #getMembers}.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization whose activity to retrieve
     * @param page           0-indexed page number, defaults to 0
     * @param size           rows per page, defaults to 20
     * @return 200 OK with the page of audit entries and a total count for pagination
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @GetMapping("/{organizationId}/events")
    public ResponseEntity<HttpResponse> getEvents(Authentication authentication,
                                                   @PathVariable Long organizationId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        requireMembershipAuthority(authentication, organizationId);
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = size == null || size <= 0 ? 20 : size;
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("events", organizationService.listOrganizationEvents(organizationId, resolvedPage, resolvedSize),
                                "totalEvents", organizationService.countOrganizationEvents(organizationId)))
                        .message("Organization activity retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns one organization's KPI tiles (member count plus its customer/invoice/revenue
     * rollups) for the dashboard-style Organizations page — same authorization rule as
     * {@link #getMembers}.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization to summarize
     * @return 200 OK with the organization's stats
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @GetMapping("/{organizationId}/stats")
    public ResponseEntity<HttpResponse> getStats(Authentication authentication, @PathVariable Long organizationId) {
        requireMembershipAuthority(authentication, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("stats", organizationService.getOrganizationStats(organizationId)))
                        .message("Organization stats retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a single-use invite for an organization — same authorization rule as
     * {@link #getMembers}, plus a role-tier ceiling: the invite can never grant a role its creator
     * could not otherwise assign directly, the same guard {@code AdminUserController} applies to a
     * direct role reassignment (see {@link RoleType#canAssign}).
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization the invite joins its redeemer to
     * @param form           the optional {roleName, ttlHours} payload; both default when omitted
     * @return 200 OK with the created invite (including its redeemable code)
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PostMapping("/{organizationId}/invites")
    public ResponseEntity<HttpResponse> createInvite(Authentication authentication,
                                                      @PathVariable Long organizationId,
                                                      @RequestBody(required = false) OrganizationInviteForm form) {
        requireMembershipAuthority(authentication, organizationId);
        UserDTO caller = getAuthenticatedUser(authentication);
        String roleName = form == null || form.getRoleName() == null || form.getRoleName().isBlank()
                ? RoleType.ROLE_USER.name() : form.getRoleName();
        long ttlHours = form == null || form.getTtlHours() == null || form.getTtlHours() <= 0
                ? 168L : form.getTtlHours();
        if (!RoleType.canAssign(caller.getRoleName(), roleName)) {
            log.warn("Caller '{}' (role {}) denied creating an invite granting role '{}' — at or above their own tier",
                    caller.getEmail(), caller.getRoleName(), roleName);
            throw new AccessDeniedException("You cannot create an invite granting a role with more privileges than your own.");
        }
        OrganizationInvite invite = organizationService.createInvite(organizationId, caller.getId(), roleName, ttlHours);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_INVITE_CREATED, roleName));
        log.info("'{}' created a {}-granting invite for organization {}", caller.getEmail(), roleName, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("invite", invite, "invites", organizationService.listActiveInvites(organizationId)))
                        .message("Invite created successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Lists an organization's outstanding invites — same authorization rule as
     * {@link #getMembers}.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization whose invites to list
     * @return 200 OK with the organization's active invites
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @GetMapping("/{organizationId}/invites")
    public ResponseEntity<HttpResponse> getInvites(Authentication authentication, @PathVariable Long organizationId) {
        requireMembershipAuthority(authentication, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("invites", organizationService.listActiveInvites(organizationId)))
                        .message("Invites retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Revokes an outstanding invite before it is redeemed — same authorization rule as
     * {@link #getMembers}.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization the invite belongs to
     * @param inviteId       the invite to revoke
     * @return 200 OK with the organization's refreshed active invites
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @DeleteMapping("/{organizationId}/invites/{inviteId}")
    public ResponseEntity<HttpResponse> revokeInvite(Authentication authentication,
                                                      @PathVariable Long organizationId,
                                                      @PathVariable Long inviteId) {
        requireMembershipAuthority(authentication, organizationId);
        organizationService.revokeInvite(organizationId, inviteId);
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_INVITE_REVOKED, null));
        log.info("'{}' revoked invite {} for organization {}", caller.getEmail(), inviteId, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("invites", organizationService.listActiveInvites(organizationId)))
                        .message("Invite revoked successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Resolves the caller's organization scope for {@link #getOrganizations}: {@code null}
     * (unscoped, full catalog) for {@link RoleType#isOrganizationScoped} tiers above the
     * unscoped floor, otherwise the ids they actively belong to.
     */
    private Collection<Long> resolveScope(UserDTO caller) {
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) {
            return null;
        }
        return organizationService.findActiveOrganizationIds(caller.getId());
    }

    /**
     * Refuses catalog mutations from anyone below the top two tiers. Absolute floor, not
     * relative to the caller's own tier — mirrors
     * {@code RoleController#requireApplicationAdmin}'s shape but keyed off
     * {@link RoleType#isOrganizationScoped} (two allowed tiers) rather than a single exact role,
     * since org CRUD was deliberately opened to {@code ROLE_ADMIN} as well as
     * {@code ROLE_APPLICATION_ADMIN}. The denial names no account data (NFR-SEC-7).
     *
     * @throws AccessDeniedException if the caller is org-scoped
     */
    private static void requireUnscopedTier(Authentication authentication) {
        UserDTO caller = getAuthenticatedUser(authentication);
        if (RoleType.isOrganizationScoped(caller.getRoleName())) {
            log.warn("Org-scoped caller '{}' (role {}) denied an organization-catalog operation", caller.getEmail(), caller.getRoleName());
            throw new AccessDeniedException("Only an administrator can manage the organization catalog.");
        }
    }

    /**
     * Refuses membership mutations unless the caller is an unscoped tier, or a
     * {@code ROLE_ORGANIZATION_ADMIN} who actively belongs to {@code organizationId} themselves.
     * A help-desk admin or moderator — also below the unscoped floor, but not
     * {@code ROLE_ORGANIZATION_ADMIN} — is refused regardless of membership: this endpoint's
     * authorization was deliberately scoped to that one role, not "any org-scoped tier", per the
     * design decision in FUTURE-ENHANCEMENTS.md §3.2. The denial names no account data
     * (NFR-SEC-7).
     *
     * @throws AccessDeniedException if the caller may not manage this organization's membership
     */
    /**
     * Resolves and bounds the org role a caller is trying to grant within one organization.
     *
     * <p>Two rules, both fail-closed:
     * <ul>
     *   <li>An unrecognized name is rejected outright rather than defaulted — silently downgrading
     *       a typo to {@code ORG_MEMBER} would make a failed promotion look like a successful one.
     *       A {@code null}/absent value is different, and legitimately means "the default".</li>
     *   <li>A scoped caller may not grant a capacity above their own, mirroring
     *       {@link RoleType#canAssign}'s ceiling on global roles and existing for the same reason:
     *       without it, {@link #requireMembershipAuthority} bounds <em>who</em> a caller may act on
     *       while leaving <em>what they may grant</em> unbounded, so an {@code ORG_ADMIN} could
     *       mint capacity they do not hold. Unscoped platform tiers are exempt — they already
     *       bypass every organization check.</li>
     * </ul>
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization the grant applies within
     * @param orgRoleName    the requested capacity, or null/blank for {@link OrgRole#DEFAULT}
     * @return the resolved role, guaranteed to be within the caller's ceiling
     * @throws ApiException         if the name is non-blank but unrecognized
     * @throws AccessDeniedException if the caller may not grant that capacity
     */
    private OrgRole requireAssignableOrgRole(Authentication authentication, Long organizationId, String orgRoleName) {
        if (orgRoleName == null || orgRoleName.isBlank()) {
            return OrgRole.DEFAULT;
        }
        OrgRole requested = OrgRole.from(orgRoleName)
                .orElseThrow(() -> new ApiException("'" + orgRoleName + "' is not a valid organization role."));
        UserDTO caller = getAuthenticatedUser(authentication);
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) {
            return requested;
        }
        String callerOrgRole = organizationService.findOrgRole(caller.getId(), organizationId)
                .map(OrgRole::name)
                .orElse(null);
        if (!OrgRole.canAssign(callerOrgRole, requested.name())) {
            log.warn("Caller '{}' (org role {}) denied granting {} in organization {}",
                    caller.getEmail(), callerOrgRole, requested, organizationId);
            throw new AccessDeniedException("You may not grant an organization role above your own.");
        }
        return requested;
    }

    private void requireMembershipAuthority(Authentication authentication, Long organizationId) {
        UserDTO caller = getAuthenticatedUser(authentication);
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) {
            return;
        }
        // NOTE(org-roles): resolved 2026-08-26. This used to read
        //   ROLE_ORGANIZATION_ADMIN.equals(globalRole) && isActiveMemberOfOrganization(...)
        // — a GLOBAL tier plus bare membership, which made "organization admin" mean admin of
        // EVERY organization the caller belonged to. The capacity now lives on the membership row
        // itself, so administering org A grants nothing in org B.
        if (organizationService.isOrgAdminOf(caller.getId(), organizationId)) {
            return;
        }
        log.warn("Caller '{}' (role {}) denied a membership operation on organization {} — not an ORG_ADMIN there",
                caller.getEmail(), caller.getRoleName(), organizationId);
        throw new AccessDeniedException("You may only manage membership for organizations you administer.");
    }
}
