package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod;
import com.bob.angularspringbootfullstack.enumeration.OrgRole;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.model.OrganizationInvite;
import com.bob.angularspringbootfullstack.model.OrganizationStats;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for {@link OrganizationController} (Organization CRUD + membership
 * management — FUTURE-ENHANCEMENTS.md §3.2), covering the two authorization rules the two
 * private helpers on that controller encode: catalog mutation is unscoped-tiers-only, while
 * membership mutation additionally admits a {@code ROLE_ORGANIZATION_ADMIN} acting on an
 * organization they themselves actively belong to.
 *
 * <p>Same {@link MockMvcBuilders#standaloneSetup} + {@link GlobalExceptionHandler} shape as
 * {@link RoleControllerTest} — the {@code AccessDeniedException} thrown by the controller's own
 * helpers, not the method-security interceptor (inactive in standalone setup), is what these 403
 * assertions exercise; the {@code UPDATE:ORGANIZATION} URL-level gate is enforced separately by
 * {@code SecurityConfig} and is not re-tested here, matching {@code RoleControllerTest}'s scope.
 */
class OrganizationControllerTest {

    private OrganizationService organizationService;
    private CustomerService customerService;
    private EmailService emailService;
    private ApplicationEventPublisher eventPublisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        organizationService = mock(OrganizationService.class);
        customerService = mock(CustomerService.class);
        emailService = mock(EmailService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        OrganizationController controller =
                new OrganizationController(organizationService, customerService, emailService, eventPublisher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication authAs(long id, String roleName) {
        UserDTO caller = new UserDTO();
        caller.setId(id);
        caller.setEmail(roleName.toLowerCase() + "@example.com");
        caller.setRoleName(roleName);
        return new UsernamePasswordAuthenticationToken(
                caller, null, AuthorityUtils.createAuthorityList("UPDATE:ORGANIZATION"));
    }

    // ── Catalog mutation: unscoped tiers only ───────────────────────────────────────────────

    @Test
    @DisplayName("an admin (unscoped tier) can create an organization with just a name")
    void adminCanCreateOrganization() throws Exception {
        Organization created = Organization.builder().id(1L).name("Acme Partners").status("ACTIVE").build();
        when(organizationService.createOrganization("Acme Partners", null, null, null, null, null, null))
                .thenReturn(created);
        when(organizationService.listOrganizations(isNull())).thenReturn(List.of());

        mockMvc.perform(post("/admin/organization")
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Partners\"}"))
                .andExpect(status().isOk());

        verify(organizationService).createOrganization("Acme Partners", null, null, null, null, null, null);
        verify(customerService, never()).assignCustomersToOrganization(any(), any());
        verify(emailService, never()).sendOrganizationCreatedEmail(any(), any(), any());
    }

    @Test
    @DisplayName("an organization admin (org-scoped tier) cannot create an organization")
    void orgAdminCannotCreateOrganization() throws Exception {
        mockMvc.perform(post("/admin/organization")
                        .principal(authAs(2L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Partners\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).createOrganization(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("creating an organization with a full setup payload attaches customers and emails the creator")
    void adminCanCreateOrganizationWithFullSetup() throws Exception {
        Organization created = Organization.builder().id(1L).name("Acme Partners").status("ACTIVE").build();
        when(organizationService.createOrganization(
                eq("Acme Partners"), eq("A partner org"), eq("contact@acme.test"), eq("https://acme.test"),
                eq("3fa85f64-5717-4562-b3fc-2c963f66afa6"), eq(Set.of(OrgMfaMethod.TOTP, OrgMfaMethod.PASSKEY)),
                eq(List.of("beta"))))
                .thenReturn(created);
        when(organizationService.listOrganizations(isNull())).thenReturn(List.of());
        when(customerService.assignCustomersToOrganization(List.of(11L, 12L), 1L)).thenReturn(2);

        mockMvc.perform(post("/admin/organization")
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Acme Partners\","
                                + "\"description\":\"A partner org\","
                                + "\"contactEmail\":\"contact@acme.test\","
                                + "\"website\":\"https://acme.test\","
                                + "\"tenantUuid\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                                + "\"mfaAllowedMethods\":[\"TOTP\",\"PASSKEY\"],"
                                + "\"featureFlags\":[\"beta\"],"
                                + "\"customerIds\":[11,12],"
                                + "\"sendConfirmationEmail\":true"
                                + "}"))
                .andExpect(status().isOk());

        verify(customerService).assignCustomersToOrganization(List.of(11L, 12L), 1L);
        verify(emailService).sendOrganizationCreatedEmail(null, "role_admin@example.com", "Acme Partners");
        verify(eventPublisher, times(2)).publishEvent(any(NewOrganizationEvent.class));
    }

    @Test
    @DisplayName("an unrecognized MFA method name on create is rejected rather than silently dropped")
    void createOrganizationRejectsUnrecognizedMfaMethod() throws Exception {
        mockMvc.perform(post("/admin/organization")
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Partners\",\"mfaAllowedMethods\":[\"CARRIER_PIGEON\"]}"))
                .andExpect(status().isBadRequest());

        verify(organizationService, never()).createOrganization(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an application admin can rename an organization")
    void applicationAdminCanRenameOrganization() throws Exception {
        Organization updated = Organization.builder().id(1L).name("New Name").status("ACTIVE").build();
        when(organizationService.renameOrganization(eq(1L), eq("New Name"))).thenReturn(updated);
        when(organizationService.listOrganizations(isNull())).thenReturn(List.of());

        mockMvc.perform(patch("/admin/organization/{id}/name", 1L)
                        .principal(authAs(1L, "ROLE_APPLICATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk());

        verify(organizationService).renameOrganization(1L, "New Name");
    }

    @Test
    @DisplayName("a help-desk admin cannot rename an organization")
    void helpDeskAdminCannotRenameOrganization() throws Exception {
        mockMvc.perform(patch("/admin/organization/{id}/name", 1L)
                        .principal(authAs(3L, "ROLE_HELP_DESK_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).renameOrganization(any(), any());
    }

    @Test
    @DisplayName("an admin can deactivate an organization")
    void adminCanSetOrganizationStatus() throws Exception {
        Organization updated = Organization.builder().id(1L).name("Acme").status("INACTIVE").build();
        when(organizationService.setOrganizationStatus(eq(1L), eq("INACTIVE"))).thenReturn(updated);
        when(organizationService.listOrganizations(isNull())).thenReturn(List.of());

        mockMvc.perform(patch("/admin/organization/{id}/status", 1L)
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        verify(organizationService).setOrganizationStatus(1L, "INACTIVE");
    }

    @Test
    @DisplayName("an organization admin cannot change an organization's status")
    void orgAdminCannotSetOrganizationStatus() throws Exception {
        mockMvc.perform(patch("/admin/organization/{id}/status", 1L)
                        .principal(authAs(2L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).setOrganizationStatus(any(), any());
    }

    // ── Membership mutation: unscoped tiers, or an org admin's own organization ────────────

    @Test
    @DisplayName("an admin (unscoped tier) can add a member to any organization")
    void adminCanAddMemberToAnyOrganization() throws Exception {
        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).addMember(9L, 42L, OrgRole.ORG_MEMBER);
        verify(organizationService, never()).isActiveMemberOfOrganization(any(), any());
    }

    @Test
    @DisplayName("an organization admin who actively belongs to the target org can add a member")
    void orgAdminCanAddMemberToOwnOrganization() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);

        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).addMember(9L, 42L, OrgRole.ORG_MEMBER);
    }

    @Test
    @DisplayName("a member holding ORG_ADMIN in one organization cannot manage a different one")
    void orgAdminAuthorityDoesNotCrossOrganizations() throws Exception {
        // The heart of TODO(org-roles): the same caller, the same global tier, two organizations.
        // Before per-org roles this pair was indistinguishable — a global ROLE_ORGANIZATION_ADMIN
        // reached every organization it belonged to.
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(organizationService.isOrgAdminOf(5L, 10L)).thenReturn(false);

        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 10L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService).addMember(9L, 42L, OrgRole.ORG_MEMBER);
        verify(organizationService, never()).addMember(eq(10L), any(), any());
    }

    @Test
    @DisplayName("an org admin can add a member at an explicit capacity within their own ceiling")
    void orgAdminCanAddMemberAtRequestedCapacity() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(organizationService.findOrgRole(5L, 9L)).thenReturn(Optional.of(OrgRole.ORG_ADMIN));

        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .param("orgRole", "ORG_ADMIN")
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).addMember(9L, 42L, OrgRole.ORG_ADMIN);
    }

    @Test
    @DisplayName("an org admin can change a member's org role in an organization they administer")
    void orgAdminCanChangeMemberOrgRole() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(organizationService.findOrgRole(5L, 9L)).thenReturn(Optional.of(OrgRole.ORG_ADMIN));

        mockMvc.perform(patch("/admin/organization/{organizationId}/members/{userId}/role", 9L, 42L)
                        .param("orgRole", "ORG_VIEWER")
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).setMemberOrgRole(9L, 42L, OrgRole.ORG_VIEWER);
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }

    @Test
    @DisplayName("an ORG_VIEWER cannot change anyone's org role, even in their own organization")
    void orgViewerCannotChangeMemberOrgRole() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(patch("/admin/organization/{organizationId}/members/{userId}/role", 9L, 42L)
                        .param("orgRole", "ORG_ADMIN")
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).setMemberOrgRole(any(), any(), any());
    }

    @Test
    @DisplayName("a scoped caller cannot grant an org role above their own capacity")
    void scopedCallerCannotGrantAboveOwnOrgRole() throws Exception {
        // Passes the membership gate, then fails the assignment ceiling — the org-level mirror of
        // RoleType.canAssign, without which scope bounds who but not what.
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(organizationService.findOrgRole(5L, 9L)).thenReturn(Optional.of(OrgRole.ORG_MEMBER));

        mockMvc.perform(patch("/admin/organization/{organizationId}/members/{userId}/role", 9L, 42L)
                        .param("orgRole", "ORG_ADMIN")
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).setMemberOrgRole(any(), any(), any());
    }

    @Test
    @DisplayName("an unscoped tier may set any org role in any organization")
    void unscopedTierMaySetAnyOrgRole() throws Exception {
        mockMvc.perform(patch("/admin/organization/{organizationId}/members/{userId}/role", 9L, 42L)
                        .param("orgRole", "ORG_ADMIN")
                        .principal(authAs(1L, "ROLE_APPLICATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).setMemberOrgRole(9L, 42L, OrgRole.ORG_ADMIN);
        verify(organizationService, never()).isOrgAdminOf(any(), any());
    }

    @Test
    @DisplayName("an unrecognized org role is rejected rather than silently defaulted")
    void unrecognizedOrgRoleIsRejected() throws Exception {
        mockMvc.perform(patch("/admin/organization/{organizationId}/members/{userId}/role", 9L, 42L)
                        .param("orgRole", "ORG_OVERLORD")
                        .principal(authAs(1L, "ROLE_APPLICATION_ADMIN")))
                .andExpect(status().isBadRequest());

        verify(organizationService, never()).setMemberOrgRole(any(), any(), any());
    }

    @Test
    @DisplayName("an organization admin who does NOT belong to the target org cannot add a member")
    void orgAdminCannotAddMemberToOtherOrganization() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).addMember(any(), any(), any());
    }

    @Test
    @DisplayName("a help-desk admin cannot add a member, even to an org they belong to")
    void helpDeskAdminCannotAddMemberRegardlessOfMembership() throws Exception {
        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(3L, "ROLE_HELP_DESK_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).addMember(any(), any(), any());
        verify(organizationService, never()).isActiveMemberOfOrganization(any(), any());
    }

    @Test
    @DisplayName("membership removal follows the same authorization rule as adding a member")
    void orgAdminCannotRemoveMemberFromOtherOrganization() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(delete("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).removeMember(any(), any());
    }

    // ── Membership read: same authorization rule as add/remove ─────────────────────────────

    @Test
    @DisplayName("an admin (unscoped tier) can list any organization's members")
    void adminCanListMembersOfAnyOrganization() throws Exception {
        when(organizationService.listActiveMembers(9L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).listActiveMembers(9L);
    }

    @Test
    @DisplayName("an organization admin can list members of an organization they actively belong to")
    void orgAdminCanListMembersOfOwnOrganization() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(organizationService.listActiveMembers(9L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).listActiveMembers(9L);
    }

    @Test
    @DisplayName("getMembers rides orgRoles alongside members, keyed by user id")
    void getMembersIncludesOrgRolesMap() throws Exception {
        when(organizationService.listActiveMembers(9L)).thenReturn(List.of());
        when(organizationService.orgRolesForOrganization(9L))
                .thenReturn(Map.of(42L, OrgRole.ORG_ADMIN, 43L, OrgRole.ORG_VIEWER));

        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgRoles.42").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.data.orgRoles.43").value("ORG_VIEWER"));

        verify(organizationService).orgRolesForOrganization(9L);
    }

    @Test
    @DisplayName("an organization admin cannot list members of an organization they do not belong to")
    void orgAdminCannotListMembersOfOtherOrganization() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).listActiveMembers(any());
        verify(organizationService, never()).orgRolesForOrganization(any());
    }

    @Test
    @DisplayName("a help-desk admin cannot list an organization's members, even one they belong to")
    void helpDeskAdminCannotListMembers() throws Exception {
        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(3L, "ROLE_HELP_DESK_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).listActiveMembers(any());
        verify(organizationService, never()).orgRolesForOrganization(any());
    }

    // ── Catalog read: scoped for org-scoped callers, unscoped for the top tiers ────────────

    @Test
    @DisplayName("an admin sees the full catalog (unscoped)")
    void adminSeesFullCatalog() throws Exception {
        when(organizationService.listOrganizations(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization").principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService, times(1)).listOrganizations(isNull());
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    @Test
    @DisplayName("an organization admin sees only the organizations they actively belong to")
    void orgAdminSeesOnlyOwnOrganizations() throws Exception {
        when(organizationService.findActiveOrganizationIds(5L)).thenReturn(List.of(9L));
        when(organizationService.listOrganizations(List.of(9L))).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization").principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).listOrganizations(List.of(9L));
    }

    // ── Profile: unscoped tiers only, same rule as catalog mutation ────────────────────────

    @Test
    @DisplayName("an admin can update an organization's profile")
    void adminCanUpdateProfile() throws Exception {
        Organization updated = Organization.builder().id(1L).name("Acme").description("desc").build();
        when(organizationService.updateOrganizationProfile(1L, "desc", null, null)).thenReturn(updated);

        mockMvc.perform(patch("/admin/organization/{id}/profile", 1L)
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"desc\"}"))
                .andExpect(status().isOk());

        verify(organizationService).updateOrganizationProfile(1L, "desc", null, null);
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }

    @Test
    @DisplayName("an organization admin cannot update an organization's profile")
    void orgAdminCannotUpdateProfile() throws Exception {
        mockMvc.perform(patch("/admin/organization/{id}/profile", 1L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"desc\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).updateOrganizationProfile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an admin can set an organization's tenant UUID")
    void adminCanSetTenantUuid() throws Exception {
        Organization updated = Organization.builder().id(1L).name("Acme").tenantUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6").build();
        when(organizationService.setTenantUuid(1L, "3fa85f64-5717-4562-b3fc-2c963f66afa6")).thenReturn(updated);

        mockMvc.perform(patch("/admin/organization/{id}/tenant-uuid", 1L)
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantUuid\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}"))
                .andExpect(status().isOk());

        verify(organizationService).setTenantUuid(1L, "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }

    @Test
    @DisplayName("an organization admin cannot set an organization's tenant UUID")
    void orgAdminCannotSetTenantUuid() throws Exception {
        mockMvc.perform(patch("/admin/organization/{id}/tenant-uuid", 1L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantUuid\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).setTenantUuid(any(), any());
    }

    @Test
    @DisplayName("an admin can update an organization's MFA policy and feature flags")
    void adminCanUpdateSettings() throws Exception {
        Organization updated = Organization.builder().id(1L).name("Acme").build();
        when(organizationService.updateOrganizationSettings(1L, Set.of(OrgMfaMethod.SMS), List.of("beta")))
                .thenReturn(updated);

        mockMvc.perform(patch("/admin/organization/{id}/settings", 1L)
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaAllowedMethods\":[\"SMS\"],\"featureFlags\":[\"beta\"]}"))
                .andExpect(status().isOk());

        verify(organizationService).updateOrganizationSettings(1L, Set.of(OrgMfaMethod.SMS), List.of("beta"));
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }

    @Test
    @DisplayName("an organization admin cannot update an organization's settings")
    void orgAdminCannotUpdateSettings() throws Exception {
        mockMvc.perform(patch("/admin/organization/{id}/settings", 1L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featureFlags\":[\"beta\"]}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).updateOrganizationSettings(any(), any(), any());
    }

    @Test
    @DisplayName("omitting mfaAllowedMethods on settings update leaves the current policy untouched")
    void updateSettingsOmittedMfaFieldPassesNull() throws Exception {
        Organization updated = Organization.builder().id(1L).name("Acme").build();
        when(organizationService.updateOrganizationSettings(1L, null, List.of("beta"))).thenReturn(updated);

        mockMvc.perform(patch("/admin/organization/{id}/settings", 1L)
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featureFlags\":[\"beta\"]}"))
                .andExpect(status().isOk());

        verify(organizationService).updateOrganizationSettings(1L, null, List.of("beta"));
    }

    // ── Events / stats: membership-authority, same rule as members ─────────────────────────

    @Test
    @DisplayName("an organization admin can read the activity log of an organization they belong to")
    void orgAdminCanReadOwnOrganizationEvents() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(organizationService.listOrganizationEvents(9L, 0, 20)).thenReturn(List.of());
        when(organizationService.countOrganizationEvents(9L)).thenReturn(0L);

        mockMvc.perform(get("/admin/organization/{organizationId}/events", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).listOrganizationEvents(9L, 0, 20);
    }

    @Test
    @DisplayName("an organization admin cannot read the activity log of an organization they do not belong to")
    void orgAdminCannotReadOtherOrganizationEvents() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(get("/admin/organization/{organizationId}/events", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).listOrganizationEvents(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an admin can read an organization's stats")
    void adminCanReadStats() throws Exception {
        when(organizationService.getOrganizationStats(9L)).thenReturn(OrganizationStats.builder().memberCount(3).build());

        mockMvc.perform(get("/admin/organization/{organizationId}/stats", 9L)
                        .principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).getOrganizationStats(9L);
    }

    @Test
    @DisplayName("an organization admin can read the customers attached to their own organization")
    void orgAdminCanReadOwnOrganizationCustomers() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        Customer customer = Customer.builder().id(11L).customerName("Jane Co").build();
        when(customerService.getCustomersForOrganizations(Set.of(9L))).thenReturn(List.of(customer));

        mockMvc.perform(get("/admin/organization/{organizationId}/customers", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customers[0].id").value(11));

        verify(customerService).getCustomersForOrganizations(Set.of(9L));
    }

    @Test
    @DisplayName("an organization admin cannot read the customers of an organization they do not belong to")
    void orgAdminCannotReadOtherOrganizationCustomers() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(get("/admin/organization/{organizationId}/customers", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(customerService, never()).getCustomersForOrganizations(any());
    }

    @Test
    @DisplayName("an organization admin can read the invoices of their own organization")
    void orgAdminCanReadOwnOrganizationInvoices() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        when(customerService.getInvoicesForOrganizations(Set.of(9L))).thenReturn(List.<Invoice>of());

        mockMvc.perform(get("/admin/organization/{organizationId}/invoices", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(customerService).getInvoicesForOrganizations(Set.of(9L));
    }

    @Test
    @DisplayName("an organization admin cannot read the invoices of an organization they do not belong to")
    void orgAdminCannotReadOtherOrganizationInvoices() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(get("/admin/organization/{organizationId}/invoices", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(customerService, never()).getInvoicesForOrganizations(any());
    }

    // ── Invites: membership-authority, plus a tier ceiling on the granted role ─────────────

    @Test
    @DisplayName("an organization admin can create a ROLE_USER invite for their own organization")
    void orgAdminCanCreateUserInvite() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);
        OrganizationInvite invite = OrganizationInvite.builder().id(1L).organizationId(9L).roleName("ROLE_USER").build();
        when(organizationService.createInvite(9L, 5L, "ROLE_USER", 168L)).thenReturn(invite);
        when(organizationService.listActiveInvites(9L)).thenReturn(List.of(invite));

        mockMvc.perform(post("/admin/organization/{organizationId}/invites", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(organizationService).createInvite(9L, 5L, "ROLE_USER", 168L);
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }

    @Test
    @DisplayName("an organization admin cannot create an invite granting a role above their own tier")
    void orgAdminCannotCreateInviteAboveOwnTier() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(true);

        mockMvc.perform(post("/admin/organization/{organizationId}/invites", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleName\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).createInvite(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("an organization admin cannot create an invite for an organization they do not belong to")
    void orgAdminCannotCreateInviteForOtherOrganization() throws Exception {
        when(organizationService.isOrgAdminOf(5L, 9L)).thenReturn(false);

        mockMvc.perform(post("/admin/organization/{organizationId}/invites", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).createInvite(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("an admin can revoke an invite")
    void adminCanRevokeInvite() throws Exception {
        when(organizationService.listActiveInvites(9L)).thenReturn(List.of());

        mockMvc.perform(delete("/admin/organization/{organizationId}/invites/{inviteId}", 9L, 3L)
                        .principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).revokeInvite(9L, 3L);
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }
}
