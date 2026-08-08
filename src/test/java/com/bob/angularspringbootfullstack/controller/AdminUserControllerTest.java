package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.PasskeyService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.UserService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for the admin "edit another user's profile" endpoint,
 * {@code PATCH /admin/user/{id}/update} ({@link AdminUserController#updateUserByAdmin}).
 * <p>
 * Two security-critical properties are locked in here:
 * <ol>
 *   <li><b>The path id is authoritative.</b> Unlike the self-service {@code PATCH /user/update}
 *       (which ignores the body id to prevent IDOR), this admin route is <em>meant</em> to target
 *       another user, so the {@code {id}} path variable must overwrite whatever id the body carried.
 *       Asserted by capturing the {@link UpdateForm} handed to the service.</li>
 *   <li><b>Self-targeting is refused.</b> An administrator must not edit their own account through
 *       the admin surface (that belongs to their profile screen), keeping the "admin endpoints act
 *       on <em>other</em> users" invariant intact. Asserted by verifying the service is never called
 *       and a 4xx is returned.</li>
 * </ol>
 * Uses {@link MockMvcBuilders#standaloneSetup} with Mockito mocks and the real
 * {@link GlobalExceptionHandler}. The {@code Authentication} is injected via
 * {@link org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder#principal} — a
 * raw {@code Authentication} parameter is a {@link java.security.Principal}, so Spring MVC resolves
 * it from the request principal without needing the security filter chain. The calling admin holds a
 * non-organization role, so the org-scope branch is a no-op and {@code OrganizationService} is never
 * consulted (that scoping is exercised by the other admin endpoints' shared helper).
 */
class AdminUserControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final long TARGET_ID = 99L;

    private UserService userService;
    private OrganizationService organizationService;
    private RoleService roleService;
    private SessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        roleService = mock(RoleService.class);
        EventService eventService = mock(EventService.class);
        organizationService = mock(OrganizationService.class);
        sessionService = mock(SessionService.class);
        PasskeyService passkeyService = mock(PasskeyService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        AdminUserController controller = new AdminUserController(userService, roleService, eventService,
                organizationService, sessionService, passkeyService, eventPublisher);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Builds an unscoped administrator (ROLE_ADMIN → org-scope check is skipped). */
    private static Authentication adminAuth() {
        UserDTO admin = new UserDTO();
        admin.setId(ADMIN_ID);
        admin.setEmail("admin@example.com");
        admin.setRoleName("ROLE_ADMIN");
        return new UsernamePasswordAuthenticationToken(
                admin, null, AuthorityUtils.createAuthorityList("UPDATE:USER"));
    }

    @Test
    @DisplayName("the {id} path variable overwrites any id present in the request body")
    void pathIdOverwritesBodyId() throws Exception {
        UserDTO refreshed = new UserDTO();
        refreshed.setId(TARGET_ID);
        refreshed.setEmail("jane@example.com");
        when(userService.updateUserDTO(any(UpdateForm.class))).thenReturn(refreshed);

        // Body carries a DIFFERENT id (123) — it must be ignored in favour of the path id (99).
        String body = "{\"id\":123,\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"email\":\"jane@example.com\"}";

        mockMvc.perform(patch("/admin/user/{id}/update", TARGET_ID)
                        .principal(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<UpdateForm> captor = org.mockito.ArgumentCaptor.forClass(UpdateForm.class);
        verify(userService).updateUserDTO(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getId())
                .as("admin endpoint must bind to the path id, not the body id")
                .isEqualTo(TARGET_ID);
    }

    @Test
    @DisplayName("an administrator cannot edit their OWN account through the admin endpoint")
    void selfTargetIsRefused() throws Exception {
        String body = "{\"firstName\":\"Admin\",\"lastName\":\"User\",\"email\":\"admin@example.com\"}";

        mockMvc.perform(patch("/admin/user/{id}/update", ADMIN_ID) // targeting self
                        .principal(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());

        verify(userService, never()).updateUserDTO(any(UpdateForm.class));
    }

    /** Builds an ORGANIZATION administrator, whose reach is bounded by shared organization membership. */
    private static Authentication orgAdminAuth() {
        UserDTO orgAdmin = new UserDTO();
        orgAdmin.setId(ADMIN_ID);
        orgAdmin.setEmail("orgadmin@example.com");
        orgAdmin.setRoleName("ROLE_ORGANIZATION_ADMIN");
        return new UsernamePasswordAuthenticationToken(
                orgAdmin, null, AuthorityUtils.createAuthorityList("UPDATE:USER"));
    }

    @Test
    @DisplayName("an org admin editing an out-of-scope user is refused with 403 (FR-ORG-2)")
    void orgAdminOutOfScopeIsForbidden() throws Exception {
        // The target shares no active organization with the calling org admin.
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(false);
        String body = "{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"email\":\"jane@example.com\"}";

        mockMvc.perform(patch("/admin/user/{id}/update", TARGET_ID)
                        .principal(orgAdminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateUserDTO(any(UpdateForm.class));
    }

    // ── Role-tier ceiling ─────────────────────────────────────────────────────────────────────
    //
    // Organization scope bounds WHO an org admin may act on; it says nothing about WHICH role they
    // may hand out. Without a ceiling, a tier-5 org admin can promote an in-scope user to
    // ROLE_ADMIN — an UNSCOPED tier-6 account — and then act through it to reach the very users
    // the scope check would have denied them. Every individual step is authorised, which is what
    // makes the composition worth blocking explicitly.
    //
    // These tests target the assignment endpoint, and each one stubs the target as IN scope, so a
    // refusal can only be the tier check rather than the scope check firing early.

    /** Lets the success path build its response without tripping Map.of's null rejection. */
    private void stubAssignmentSuccessPath() {
        UserDTO target = new UserDTO();
        target.setId(TARGET_ID);
        target.setEmail("target@example.com");
        when(userService.getUserById(TARGET_ID)).thenReturn(target);
        when(roleService.getAllRoles()).thenReturn(java.util.List.of());
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("an org admin (tier 5) cannot promote anyone to ROLE_ADMIN (tier 6)")
    void orgAdminCannotAssignAboveOwnTier() throws Exception {
        // In scope, so the ONLY thing that can refuse this is the tier ceiling.
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(true);

        mockMvc.perform(patch("/admin/user/{id}/role/{roleName}", TARGET_ID, "ROLE_ADMIN")
                        .principal(orgAdminAuth()))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateUserRole(any(Long.class), any(String.class));
    }

    @Test
    @DisplayName("an org admin CAN assign a role below their own tier")
    void orgAdminCanAssignBelowOwnTier() throws Exception {
        stubAssignmentSuccessPath();

        mockMvc.perform(patch("/admin/user/{id}/role/{roleName}", TARGET_ID, "ROLE_MODERATOR")
                        .principal(orgAdminAuth()))
                .andExpect(status().isOk());

        verify(userService).updateUserRole(TARGET_ID, "ROLE_MODERATOR");
    }

    @Test
    @DisplayName("an equal tier is assignable — an admin may create a peer, just not a superior")
    void equalTierIsAssignable() throws Exception {
        stubAssignmentSuccessPath();

        mockMvc.perform(patch("/admin/user/{id}/role/{roleName}", TARGET_ID, "ROLE_ORGANIZATION_ADMIN")
                        .principal(orgAdminAuth()))
                .andExpect(status().isOk());

        verify(userService).updateUserRole(TARGET_ID, "ROLE_ORGANIZATION_ADMIN");
    }

    @Test
    @DisplayName("an unrecognised role name is refused — the ceiling fails closed")
    void unknownRoleIsRefused() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(true);

        mockMvc.perform(patch("/admin/user/{id}/role/{roleName}", TARGET_ID, "ROLE_SUPERUSER")
                        .principal(orgAdminAuth()))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateUserRole(any(Long.class), any(String.class));
    }

    // ── Admin session revocation ──────────────────────────────────────────────────────────────
    //
    // Locking an account stops the NEXT sign-in but does nothing to sessions already open: access
    // tokens verify by signature alone, and the holder's refresh token keeps minting new ones for
    // five days. Revoking the refresh families is what actually ends an intrusion.

    @Test
    @DisplayName("an admin can sign a user out of every device")
    void adminCanRevokeAllSessionsForAUser() throws Exception {
        stubAssignmentSuccessPath();

        mockMvc.perform(delete("/admin/user/{id}/sessions", TARGET_ID)
                        .principal(adminAuth()))
                .andExpect(status().isOk());

        verify(sessionService).revokeAllSessions(TARGET_ID);
    }

    @Test
    @DisplayName("an org admin cannot revoke sessions for an out-of-scope user")
    void revokeSessionsRespectsOrganizationScope() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(false);

        mockMvc.perform(delete("/admin/user/{id}/sessions", TARGET_ID)
                        .principal(orgAdminAuth()))
                .andExpect(status().isForbidden());

        verify(sessionService, never()).revokeAllSessions(any(Long.class));
    }

    @Test
    @DisplayName("an admin cannot revoke their OWN sessions here — that would sign them out mid-request")
    void revokeSessionsRefusesSelfTarget() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/sessions", ADMIN_ID) // targeting self
                        .principal(adminAuth()))
                .andExpect(status().is4xxClientError());

        verify(sessionService, never()).revokeAllSessions(any(Long.class));
    }

    @Test
    @DisplayName("the top tier can still assign everything below it")
    void applicationAdminCanAssignAnything() throws Exception {
        stubAssignmentSuccessPath();

        UserDTO appAdmin = new UserDTO();
        appAdmin.setId(ADMIN_ID);
        appAdmin.setEmail("appadmin@example.com");
        appAdmin.setRoleName("ROLE_APPLICATION_ADMIN");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                appAdmin, null, AuthorityUtils.createAuthorityList("UPDATE:ROLE"));

        mockMvc.perform(patch("/admin/user/{id}/role/{roleName}", TARGET_ID, "ROLE_ADMIN")
                        .principal(auth))
                .andExpect(status().isOk());

        verify(userService).updateUserRole(TARGET_ID, "ROLE_ADMIN");
    }
}
