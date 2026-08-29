package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
import com.bob.angularspringbootfullstack.service.FederatedLoginCompletionService;
import com.bob.angularspringbootfullstack.service.UserService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@link OAuth2LoginSuccessHandler} after the Stage 3 (SAML) extraction moved
 * its protocol-agnostic tail into {@link FederatedLoginCompletionService}: this suite now covers only
 * what is left in this class — per-provider profile extraction (including the {@code org-oidc-*}
 * generic-OIDC branch), the account-link handshake, and delegating to
 * {@link FederatedLoginCompletionService} for everything after {@code findOrCreateFederatedUser}
 * resolves a user. Auto-join/MFA/token-issuance behavior now lives in
 * {@code FederatedLoginCompletionServiceImplTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private FederatedIdentityService federatedIdentityService;
    @Mock
    private UserService userService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private FederatedLoginCompletionService federatedLoginCompletionService;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private OAuth2AuthenticationToken authentication;
    @Mock
    private OAuth2User principal;

    private OAuth2LoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginSuccessHandler(
                federatedIdentityService, userService, eventPublisher, federatedLoginCompletionService);
        ReflectionTestUtils.setField(handler, "uiAppUrl", "http://localhost:4200");

        when(request.getSession(false)).thenReturn(null);
        when(authentication.getPrincipal()).thenReturn(principal);
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
    @DisplayName("an org-oidc login extracts the profile from generic OIDC standard claims")
    void extractsGenericOidcClaimsForOrgProvider() throws Exception {
        when(authentication.getAuthorizedClientRegistrationId()).thenReturn("org-oidc-42");
        when(principal.getName()).thenReturn("oidc-subject-123");
        when(principal.getAttribute("email")).thenReturn("member@acme.com");
        when(principal.getAttribute("given_name")).thenReturn("Ada");
        when(principal.getAttribute("family_name")).thenReturn("Lovelace");
        when(principal.getAttribute("picture")).thenReturn("https://acme.example.com/ada.png");
        when(federatedIdentityService.findOrCreateFederatedUser(
                "org-oidc-42", "oidc-subject-123", "member@acme.com", "Ada", "Lovelace", "https://acme.example.com/ada.png"))
                .thenReturn(activeUser(99L));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedIdentityService).findOrCreateFederatedUser(
                "org-oidc-42", "oidc-subject-123", "member@acme.com", "Ada", "Lovelace", "https://acme.example.com/ada.png");
        verify(federatedLoginCompletionService).completeLogin(eq("org-oidc-42"), any(UserDTO.class), eq(request), eq(response));
    }

    @Test
    @DisplayName("a google login extracts the profile via the provider-specific attribute mapping and delegates completion")
    void extractsGoogleProfileAndDelegatesCompletion() throws Exception {
        when(authentication.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(principal.getAttribute("email")).thenReturn("member@example.com");
        when(principal.getAttribute("given_name")).thenReturn("Ada");
        when(principal.getAttribute("family_name")).thenReturn("Lovelace");
        when(principal.getName()).thenReturn("google-subject-123");
        when(federatedIdentityService.findOrCreateFederatedUser(any(), any(), any(), any(), any(), any()))
                .thenReturn(activeUser(7L));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedLoginCompletionService).completeLogin(eq("google"), any(UserDTO.class), eq(request), eq(response));
    }

    @Test
    @DisplayName("a failure anywhere in the flow redirects to the SPA login screen with a coarse error code, never a completion call")
    void failureRedirectsWithoutCompletingLogin() throws Exception {
        when(authentication.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(principal.getAttribute("email")).thenReturn("member@example.com");
        when(principal.getName()).thenReturn("google-subject-123");
        when(federatedIdentityService.findOrCreateFederatedUser(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://localhost:4200/login?error=federated");
        verify(federatedLoginCompletionService, never()).completeLogin(anyString(), any(), any(), any());
    }
}
