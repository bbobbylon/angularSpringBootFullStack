package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.service.RoleService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for the Role CRUD catalog endpoints ({@link RoleController}), which are
 * deliberately gated <b>tighter</b> than the rest of {@code /admin/**}.
 *
 * <h3>The property this suite exists for</h3>
 * Every other {@code /admin/**} mutation this application has needs only the {@code UPDATE:ROLE}
 * or {@code UPDATE:USER} authority — several role tiers hold one of those. Role-catalog mutation
 * is narrower on purpose (FUTURE-ENHANCEMENTS.md §3.2): it needs {@code UPDATE:ROLE} <em>and</em>
 * the caller's role must be exactly {@code ROLE_APPLICATION_ADMIN}, the single highest tier.
 * {@link #regularAdminCannotCreateRoleDespiteHoldingUpdateRoleAuthority()} is the test that would
 * fail first if that extra check were ever accidentally dropped — a plain {@code
 * hasAuthority('UPDATE:ROLE')} {@code @PreAuthorize}, the pattern used everywhere else in this
 * controller family, would let a regular {@code ROLE_ADMIN} through.
 *
 * <p>Uses {@link MockMvcBuilders#standaloneSetup} with a Mockito mock and the real {@link
 * GlobalExceptionHandler}, matching {@code AdminUserControllerTest}'s convention. The {@code
 * Authentication} is injected via {@code principal(...)} — a raw {@code Authentication} controller
 * parameter is a {@link java.security.Principal}, so Spring MVC resolves it from the request
 * principal without needing the security filter chain.
 */
class RoleControllerTest {

    private RoleService roleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        RoleController controller = new RoleController(roleService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication applicationAdminAuth() {
        UserDTO admin = new UserDTO();
        admin.setId(1L);
        admin.setEmail("appadmin@example.com");
        admin.setRoleName("ROLE_APPLICATION_ADMIN");
        return new UsernamePasswordAuthenticationToken(
                admin, null, AuthorityUtils.createAuthorityList("UPDATE:ROLE"));
    }

    /** Holds the same UPDATE:ROLE authority as {@link #applicationAdminAuth()} but a lower tier. */
    private static Authentication regularAdminAuth() {
        UserDTO admin = new UserDTO();
        admin.setId(2L);
        admin.setEmail("admin@example.com");
        admin.setRoleName("ROLE_ADMIN");
        return new UsernamePasswordAuthenticationToken(
                admin, null, AuthorityUtils.createAuthorityList("UPDATE:ROLE"));
    }

    @Test
    @DisplayName("an application admin can create a role")
    void applicationAdminCanCreateRole() throws Exception {
        Role created = Role.builder().id(10L).name("ROLE_BILLING_REVIEWER").permission("READ:CUSTOMER").build();
        when(roleService.createRole(any(Role.class))).thenReturn(created);
        when(roleService.getAllRoles()).thenReturn(List.of());

        mockMvc.perform(post("/admin/role")
                        .principal(applicationAdminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ROLE_BILLING_REVIEWER\",\"permission\":\"READ:CUSTOMER\"}"))
                .andExpect(status().isOk());

        verify(roleService).createRole(any(Role.class));
    }

    @Test
    @DisplayName("a regular admin holding UPDATE:ROLE is still refused — the catalog needs the top tier specifically")
    void regularAdminCannotCreateRoleDespiteHoldingUpdateRoleAuthority() throws Exception {
        mockMvc.perform(post("/admin/role")
                        .principal(regularAdminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ROLE_BILLING_REVIEWER\",\"permission\":\"READ:CUSTOMER\"}"))
                .andExpect(status().isForbidden());

        verify(roleService, never()).createRole(any(Role.class));
    }

    @Test
    @DisplayName("an application admin can update a role's permission string")
    void applicationAdminCanUpdatePermission() throws Exception {
        Role updated = Role.builder().id(3L).name("ROLE_MODERATOR").permission("READ:CUSTOMER,UPDATE:CUSTOMER").build();
        when(roleService.updateRolePermission(eq(3L), anyString())).thenReturn(updated);
        when(roleService.getAllRoles()).thenReturn(List.of());

        mockMvc.perform(patch("/admin/role/{id}", 3L)
                        .principal(applicationAdminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission\":\"READ:CUSTOMER,UPDATE:CUSTOMER\"}"))
                .andExpect(status().isOk());

        verify(roleService).updateRolePermission(3L, "READ:CUSTOMER,UPDATE:CUSTOMER");
    }

    @Test
    @DisplayName("a regular admin cannot update a role's permission string")
    void regularAdminCannotUpdatePermission() throws Exception {
        mockMvc.perform(patch("/admin/role/{id}", 3L)
                        .principal(regularAdminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission\":\"READ:CUSTOMER\"}"))
                .andExpect(status().isForbidden());

        verify(roleService, never()).updateRolePermission(any(Long.class), anyString());
    }

    @Test
    @DisplayName("an application admin can delete a role")
    void applicationAdminCanDeleteRole() throws Exception {
        when(roleService.getAllRoles()).thenReturn(List.of());

        mockMvc.perform(delete("/admin/role/{id}", 11L)
                        .principal(applicationAdminAuth()))
                .andExpect(status().isOk());

        verify(roleService).deleteRole(11L);
    }

    @Test
    @DisplayName("a regular admin cannot delete a role")
    void regularAdminCannotDeleteRole() throws Exception {
        mockMvc.perform(delete("/admin/role/{id}", 11L)
                        .principal(regularAdminAuth()))
                .andExpect(status().isForbidden());

        verify(roleService, never()).deleteRole(any(Long.class));
    }
}
