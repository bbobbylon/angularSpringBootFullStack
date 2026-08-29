package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.model.OrganizationIdentityProvider;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for {@link OrganizationIdentityProviderController} (per-organization SSO —
 * FUTURE-ENHANCEMENTS.md §3.1), covering {@code requireOrgAdmin}: unscoped tiers may manage any
 * organization's SSO configuration, an org-scoped {@code ROLE_ORGANIZATION_ADMIN} may manage only
 * the organizations they themselves administer, and every other org-scoped caller is refused
 * outright — deliberately stricter than {@link OrganizationController}'s membership-mutation gate,
 * which admits plain membership for some reads; this controller never does.
 * <p>
 * Same {@link MockMvcBuilders#standaloneSetup} + {@link GlobalExceptionHandler} shape as
 * {@link OrganizationControllerTest} — exercises the controller's own {@code AccessDeniedException},
 * not the method-security interceptor, which is inactive in standalone setup.
 */
class OrganizationIdentityProviderControllerTest {

    private OrganizationIdentityProviderService ssoService;
    private OrganizationService organizationService;
    private ApplicationEventPublisher eventPublisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ssoService = mock(OrganizationIdentityProviderService.class);
        organizationService = mock(OrganizationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        OrganizationIdentityProviderController controller =
                new OrganizationIdentityProviderController(ssoService, organizationService, eventPublisher);
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

    @Test
    @DisplayName("an unscoped admin may view any organization's SSO configuration")
    void unscopedAdminCanView() throws Exception {
        when(ssoService.getConfig(7L)).thenReturn(Optional.empty());
        when(ssoService.listDomains(7L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization/7/sso").principal(authAs(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ORGANIZATION_ADMIN who administers this organization may view its SSO configuration")
    void orgAdminOfThisOrgCanView() throws Exception {
        when(organizationService.isOrgAdminOf(2L, 7L)).thenReturn(true);
        when(ssoService.getConfig(7L)).thenReturn(Optional.empty());
        when(ssoService.listDomains(7L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/organization/7/sso").principal(authAs(2L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ORGANIZATION_ADMIN of a different organization is refused")
    void orgAdminOfAnotherOrgIsRefused() throws Exception {
        when(organizationService.isOrgAdminOf(2L, 7L)).thenReturn(false);

        mockMvc.perform(get("/admin/organization/7/sso").principal(authAs(2L, "ROLE_ORGANIZATION_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an org-scoped role below ORGANIZATION_ADMIN is refused regardless of membership")
    void orgScopedNonAdminIsRefused() throws Exception {
        mockMvc.perform(get("/admin/organization/7/sso").principal(authAs(3L, "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unscoped admin can configure OIDC SSO for an organization")
    void unscopedAdminCanConfigureSso() throws Exception {
        OrganizationIdentityProvider saved = OrganizationIdentityProvider.builder()
                .id(1L).organizationId(7L).protocol("OIDC").displayName("Acme Okta")
                .status("ACTIVE").oidcIssuerUri("https://acme.okta.com").oidcClientId("client-id")
                .secretConfigured(true).build();
        when(ssoService.upsertOidcConfig(anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(saved);

        mockMvc.perform(put("/admin/organization/7/sso")
                        .principal(authAs(1L, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Acme Okta","issuerUri":"https://acme.okta.com","clientId":"client-id","clientSecret":"s3cret"}
                                """))
                .andExpect(status().isOk());
    }
}
