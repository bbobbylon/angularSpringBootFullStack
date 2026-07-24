package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.RoleService;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        RoleService roleService = mock(RoleService.class);
        EventService eventService = mock(EventService.class);
        organizationService = mock(OrganizationService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        AdminUserController controller = new AdminUserController(userService, roleService, eventService,
                organizationService, eventPublisher);

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
}
