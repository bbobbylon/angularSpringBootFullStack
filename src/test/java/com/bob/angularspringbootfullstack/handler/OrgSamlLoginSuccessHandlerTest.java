package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
import com.bob.angularspringbootfullstack.service.FederatedLoginCompletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@link OrgSamlLoginSuccessHandler} (FUTURE-ENHANCEMENTS.md §3.1, Stage 3):
 * covers the multi-IdP attribute-name fallback chain for email/given-name/surname, the NameID email
 * fallback, and that a resolved user is always handed off to
 * {@link FederatedLoginCompletionService} — the same contract
 * {@code OAuth2LoginSuccessHandlerTest} verifies for the OIDC path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgSamlLoginSuccessHandlerTest {

    @Mock
    private FederatedIdentityService federatedIdentityService;
    @Mock
    private FederatedLoginCompletionService federatedLoginCompletionService;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private Saml2Authentication authentication;
    @Mock
    private Saml2AuthenticatedPrincipal principal;

    private OrgSamlLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrgSamlLoginSuccessHandler(federatedIdentityService, federatedLoginCompletionService);
        ReflectionTestUtils.setField(handler, "uiAppUrl", "http://localhost:4200");

        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getRelyingPartyRegistrationId()).thenReturn("org-saml-42");
    }

    private static UserDTO activeUser(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setEmail("member@acme.com");
        user.setEnabled(true);
        user.setNotLocked(true);
        return user;
    }

    @Test
    @DisplayName("email/given-name/surname are read from the informal attribute names when present")
    void extractsProfileFromInformalAttributeNames() throws Exception {
        when(principal.getName()).thenReturn("saml-subject-123");
        when(principal.getAttribute("email")).thenReturn(List.of("member@acme.com"));
        when(principal.getAttribute("givenName")).thenReturn(List.of("Ada"));
        when(principal.getAttribute("surname")).thenReturn(List.of("Lovelace"));
        when(federatedIdentityService.findOrCreateFederatedUser(
                "org-saml-42", "saml-subject-123", "member@acme.com", "Ada", "Lovelace", null))
                .thenReturn(activeUser(99L));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedIdentityService).findOrCreateFederatedUser(
                "org-saml-42", "saml-subject-123", "member@acme.com", "Ada", "Lovelace", null);
        verify(federatedLoginCompletionService).completeLogin(eq("org-saml-42"), any(UserDTO.class), eq(request), eq(response));
    }

    @Test
    @DisplayName("email falls back to the X.500 OID attribute name when the informal name is absent")
    void fallsBackToOidEmailAttribute() throws Exception {
        when(principal.getName()).thenReturn("saml-subject-123");
        when(principal.getAttribute("urn:oid:0.9.2342.19200300.100.1.3")).thenReturn(List.of("mail@acme.com"));
        when(federatedIdentityService.findOrCreateFederatedUser(any(), any(), any(), any(), any(), any()))
                .thenReturn(activeUser(99L));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedIdentityService).findOrCreateFederatedUser(
                eq("org-saml-42"), eq("saml-subject-123"), eq("mail@acme.com"), anyString(), anyString(), eq(null));
    }

    @Test
    @DisplayName("a missing email attribute falls back to the NameID (many IdPs use the email NameID format)")
    void fallsBackToNameIdForEmail() throws Exception {
        when(principal.getName()).thenReturn("user@acme.com");
        when(federatedIdentityService.findOrCreateFederatedUser(any(), any(), any(), any(), any(), any()))
                .thenReturn(activeUser(99L));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedIdentityService).findOrCreateFederatedUser(
                eq("org-saml-42"), eq("user@acme.com"), eq("user@acme.com"), anyString(), anyString(), eq(null));
    }

    @Test
    @DisplayName("missing given-name/surname attributes fall back to generic placeholders, not null")
    void fallsBackToPlaceholderNamesWhenAbsent() throws Exception {
        when(principal.getName()).thenReturn("saml-subject-123");
        when(principal.getAttribute("email")).thenReturn(List.of("member@acme.com"));
        when(federatedIdentityService.findOrCreateFederatedUser(any(), any(), any(), any(), any(), any()))
                .thenReturn(activeUser(99L));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedIdentityService).findOrCreateFederatedUser(
                "org-saml-42", "saml-subject-123", "member@acme.com", "Organization", "Member", null);
    }

    @Test
    @DisplayName("a failure anywhere in the flow redirects to the SPA login screen, never completing login")
    void failureRedirectsWithoutCompletingLogin() throws Exception {
        when(principal.getName()).thenReturn("saml-subject-123");
        when(federatedIdentityService.findOrCreateFederatedUser(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://localhost:4200/login?error=federated");
        verify(federatedLoginCompletionService, never()).completeLogin(anyString(), any(), any(), any());
    }
}
