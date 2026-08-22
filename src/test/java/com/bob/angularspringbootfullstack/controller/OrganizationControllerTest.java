package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        organizationService = mock(OrganizationService.class);
        OrganizationController controller = new OrganizationController(organizationService);
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
    @DisplayName("an admin (unscoped tier) can create an organization")
    void adminCanCreateOrganization() throws Exception {
        Organization created = Organization.builder().id(1L).name("Acme Partners").status("ACTIVE").build();
        when(organizationService.createOrganization("Acme Partners")).thenReturn(created);
        when(organizationService.listOrganizations(isNull())).thenReturn(List.of());

        mockMvc.perform(post("/admin/organization")
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Partners\"}"))
                .andExpect(status().isOk());

        verify(organizationService).createOrganization("Acme Partners");
    }

    @Test
    @DisplayName("an organization admin (org-scoped tier) cannot create an organization")
    void orgAdminCannotCreateOrganization() throws Exception {
        mockMvc.perform(post("/admin/organization")
                        .principal(authAs(2L, "ROLE_ORGANIZATION_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Partners\"}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).createOrganization(any());
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

        verify(organizationService).addMember(9L, 42L);
        verify(organizationService, never()).isActiveMemberOfOrganization(any(), any());
    }

    @Test
    @DisplayName("an organization admin who actively belongs to the target org can add a member")
    void orgAdminCanAddMemberToOwnOrganization() throws Exception {
        when(organizationService.isActiveMemberOfOrganization(5L, 9L)).thenReturn(true);

        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).addMember(9L, 42L);
    }

    @Test
    @DisplayName("an organization admin who does NOT belong to the target org cannot add a member")
    void orgAdminCannotAddMemberToOtherOrganization() throws Exception {
        when(organizationService.isActiveMemberOfOrganization(5L, 9L)).thenReturn(false);

        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).addMember(any(), any());
    }

    @Test
    @DisplayName("a help-desk admin cannot add a member, even to an org they belong to")
    void helpDeskAdminCannotAddMemberRegardlessOfMembership() throws Exception {
        mockMvc.perform(post("/admin/organization/{organizationId}/members/{userId}", 9L, 42L)
                        .principal(authAs(3L, "ROLE_HELP_DESK_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).addMember(any(), any());
        verify(organizationService, never()).isActiveMemberOfOrganization(any(), any());
    }

    @Test
    @DisplayName("membership removal follows the same authorization rule as adding a member")
    void orgAdminCannotRemoveMemberFromOtherOrganization() throws Exception {
        when(organizationService.isActiveMemberOfOrganization(5L, 9L)).thenReturn(false);

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
        when(organizationService.isActiveMemberOfOrganization(5L, 9L)).thenReturn(true);
        when(organizationService.listActiveMembers(9L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());

        verify(organizationService).listActiveMembers(9L);
    }

    @Test
    @DisplayName("an organization admin cannot list members of an organization they do not belong to")
    void orgAdminCannotListMembersOfOtherOrganization() throws Exception {
        when(organizationService.isActiveMemberOfOrganization(5L, 9L)).thenReturn(false);

        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(5L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).listActiveMembers(any());
    }

    @Test
    @DisplayName("a help-desk admin cannot list an organization's members, even one they belong to")
    void helpDeskAdminCannotListMembers() throws Exception {
        mockMvc.perform(get("/admin/organization/{organizationId}/members", 9L)
                        .principal(authAs(3L, "ROLE_HELP_DESK_ADMIN")))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).listActiveMembers(any());
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
}
