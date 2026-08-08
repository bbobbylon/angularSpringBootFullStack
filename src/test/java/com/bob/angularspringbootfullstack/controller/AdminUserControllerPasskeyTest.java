package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the admin passkey-revocation endpoints ({@link AdminUserController#revokeUserPasskey} and
 * {@link AdminUserController#revokeAllUserPasskeys}) — the "help reset" capability. There is no
 * "regenerate a passkey" test because there is no such operation: a passkey's private key never
 * leaves its authenticator, so revocation is the only lever anyone, including an administrator, has
 * (see both endpoints' Javadoc).
 *
 * <p>Same {@code standaloneSetup} + real {@link GlobalExceptionHandler} pattern as
 * {@link AdminUserControllerTest}, whose self-target and organization-scope suites this one
 * deliberately does not re-derive from scratch — it asserts the same three properties
 * ({@code UPDATE:USER} required, self-target refused, organization scope enforced) apply to these
 * two new routes specifically, since each endpoint wires those checks independently and a route can
 * be added without one of them by mistake.
 */
class AdminUserControllerPasskeyTest {

    private static final long ADMIN_ID = 1L;
    private static final long TARGET_ID = 99L;

    private PasskeyService passkeyService;
    private OrganizationService organizationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        RoleService roleService = mock(RoleService.class);
        EventService eventService = mock(EventService.class);
        organizationService = mock(OrganizationService.class);
        SessionService sessionService = mock(SessionService.class);
        passkeyService = mock(PasskeyService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        UserDTO target = new UserDTO();
        target.setId(TARGET_ID);
        target.setEmail("target@example.com");
        when(userService.getUserById(TARGET_ID)).thenReturn(target);
        when(passkeyService.listCredentials(anyLong())).thenReturn(List.of());

        AdminUserController controller = new AdminUserController(userService, roleService, eventService,
                organizationService, sessionService, passkeyService, eventPublisher);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication adminAuth() {
        UserDTO admin = new UserDTO();
        admin.setId(ADMIN_ID);
        admin.setEmail("admin@example.com");
        admin.setRoleName("ROLE_ADMIN");
        return new UsernamePasswordAuthenticationToken(
                admin, null, AuthorityUtils.createAuthorityList("UPDATE:USER"));
    }

    private static Authentication orgAdminAuth() {
        UserDTO orgAdmin = new UserDTO();
        orgAdmin.setId(ADMIN_ID);
        orgAdmin.setEmail("orgadmin@example.com");
        orgAdmin.setRoleName("ROLE_ORGANIZATION_ADMIN");
        return new UsernamePasswordAuthenticationToken(
                orgAdmin, null, AuthorityUtils.createAuthorityList("UPDATE:USER"));
    }

    @Test
    @DisplayName("an admin can revoke a single passkey belonging to another user")
    void adminCanRevokeOnePasskey() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/passkeys/{credentialId}", TARGET_ID, 5L)
                        .principal(adminAuth()))
                .andExpect(status().isOk());

        verify(passkeyService).deleteCredential(TARGET_ID, 5L);
    }

    @Test
    @DisplayName("an admin can revoke ALL passkeys belonging to another user")
    void adminCanRevokeAllPasskeys() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/passkeys", TARGET_ID)
                        .principal(adminAuth()))
                .andExpect(status().isOk());

        verify(passkeyService).deleteAllCredentials(TARGET_ID);
    }

    @Test
    @DisplayName("an admin cannot revoke their OWN passkeys through this endpoint")
    void selfTargetIsRefusedForSinglePasskey() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/passkeys/{credentialId}", ADMIN_ID, 5L)
                        .principal(adminAuth()))
                .andExpect(status().is4xxClientError());

        verify(passkeyService, never()).deleteCredential(any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("an admin cannot revoke ALL of their OWN passkeys through this endpoint")
    void selfTargetIsRefusedForAllPasskeys() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/passkeys", ADMIN_ID)
                        .principal(adminAuth()))
                .andExpect(status().is4xxClientError());

        verify(passkeyService, never()).deleteAllCredentials(any(Long.class));
    }

    @Test
    @DisplayName("an org admin cannot revoke passkeys for an out-of-scope user")
    void orgScopeIsEnforcedForSinglePasskey() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(false);

        mockMvc.perform(delete("/admin/user/{id}/passkeys/{credentialId}", TARGET_ID, 5L)
                        .principal(orgAdminAuth()))
                .andExpect(status().isForbidden());

        verify(passkeyService, never()).deleteCredential(any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("an org admin cannot revoke ALL passkeys for an out-of-scope user")
    void orgScopeIsEnforcedForAllPasskeys() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(false);

        mockMvc.perform(delete("/admin/user/{id}/passkeys", TARGET_ID)
                        .principal(orgAdminAuth()))
                .andExpect(status().isForbidden());

        verify(passkeyService, never()).deleteAllCredentials(any(Long.class));
    }

    @Test
    @DisplayName("an org admin CAN revoke passkeys for an in-scope user")
    void orgAdminCanRevokeInScopeUserPasskeys() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(true);

        mockMvc.perform(delete("/admin/user/{id}/passkeys", TARGET_ID)
                        .principal(orgAdminAuth()))
                .andExpect(status().isOk());

        verify(passkeyService).deleteAllCredentials(TARGET_ID);
    }
}
