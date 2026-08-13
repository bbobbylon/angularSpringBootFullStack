package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.PasskeyService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards {@link AdminUserController#resetUserTotp} — the admin recovery path for an account that
 * has lost both its authenticator and every recovery code, and so has no live code to present
 * through the self-service disable flow at all ({@link TotpService#disableTotp} deliberately
 * requires one).
 *
 * <p>Same {@code standaloneSetup} + real {@link GlobalExceptionHandler} pattern as
 * {@link AdminUserControllerPasskeyTest}, whose sibling endpoints this test asserts the identical
 * three properties against ({@code UPDATE:USER} required, self-target refused, organization scope
 * enforced) — this route wires those checks independently of the passkey routes, so nothing here
 * is inherited automatically; a route can be added without one of them by mistake.
 */
class AdminUserControllerTotpResetTest {

    private static final long ADMIN_ID = 1L;
    private static final long TARGET_ID = 99L;

    private TotpService totpService;
    private OrganizationService organizationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        RoleService roleService = mock(RoleService.class);
        EventService eventService = mock(EventService.class);
        organizationService = mock(OrganizationService.class);
        SessionService sessionService = mock(SessionService.class);
        PasskeyService passkeyService = mock(PasskeyService.class);
        totpService = mock(TotpService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        UserDTO target = new UserDTO();
        target.setId(TARGET_ID);
        target.setEmail("target@example.com");
        when(userService.getUserById(TARGET_ID)).thenReturn(target);

        AdminUserController controller = new AdminUserController(userService, roleService, eventService,
                organizationService, sessionService, passkeyService, totpService, eventPublisher);

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
    @DisplayName("an admin can reset TOTP for another user, with no code required")
    void adminCanResetTotp() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/totp", TARGET_ID)
                        .principal(adminAuth()))
                .andExpect(status().isOk());

        verify(totpService).adminResetTotp(TARGET_ID);
    }

    @Test
    @DisplayName("an admin cannot reset their OWN TOTP through this endpoint")
    void selfTargetIsRefused() throws Exception {
        mockMvc.perform(delete("/admin/user/{id}/totp", ADMIN_ID)
                        .principal(adminAuth()))
                .andExpect(status().is4xxClientError());

        verify(totpService, never()).adminResetTotp(any(Long.class));
    }

    @Test
    @DisplayName("an org admin cannot reset TOTP for an out-of-scope user")
    void orgScopeIsEnforced() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(false);

        mockMvc.perform(delete("/admin/user/{id}/totp", TARGET_ID)
                        .principal(orgAdminAuth()))
                .andExpect(status().isForbidden());

        verify(totpService, never()).adminResetTotp(any(Long.class));
    }

    @Test
    @DisplayName("an org admin CAN reset TOTP for an in-scope user")
    void orgAdminCanResetInScopeUserTotp() throws Exception {
        when(organizationService.isWithinOrganizationScope(ADMIN_ID, TARGET_ID)).thenReturn(true);

        mockMvc.perform(delete("/admin/user/{id}/totp", TARGET_ID)
                        .principal(orgAdminAuth()))
                .andExpect(status().isOk());

        verify(totpService).adminResetTotp(TARGET_ID);
    }
}
