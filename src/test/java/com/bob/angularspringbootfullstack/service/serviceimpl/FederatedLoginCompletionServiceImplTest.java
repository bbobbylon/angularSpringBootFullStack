package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_MEMBER_ADDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@link FederatedLoginCompletionServiceImpl} — the protocol-agnostic tail
 * extracted out of {@code OAuth2LoginSuccessHandler} when SAML (Stage 3) needed the identical
 * resolve-user-outcome → ensure-org-membership → mint-tokens-or-challenge-MFA → redirect sequence.
 * Covers both the {@code org-oidc-*} and {@code org-saml-*} auto-join prefixes, since this class is
 * now the single place that logic lives.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FederatedLoginCompletionServiceImplTest {

    @Mock
    private OrganizationService organizationService;
    @Mock
    private RoleService roleService;
    @Mock
    private SessionService sessionService;
    @Mock
    private TotpService totpService;
    @Mock
    private UserService userService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private FederatedLoginCompletionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FederatedLoginCompletionServiceImpl(
                organizationService, roleService, sessionService, totpService, userService, eventPublisher);
        ReflectionTestUtils.setField(service, "uiAppUrl", "http://localhost:4200");

        when(roleService.getRoleByUserId(any())).thenReturn(mock(Role.class));
        when(sessionService.issueTokenPair(any(), any()))
                .thenReturn(new SessionService.TokenPair("access-token", "refresh-token", activeUser(99L)));
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
    @DisplayName("a first-time org-oidc login auto-joins the organization and audits ORG_MEMBER_ADDED")
    void autoJoinsAndAuditsOnFirstTimeOidcLogin() throws Exception {
        when(organizationService.ensureAutoJoinMembership(42L, 99L)).thenReturn(true);

        service.completeLogin("org-oidc-42", activeUser(99L), request, response);

        verify(organizationService).ensureAutoJoinMembership(42L, 99L);
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).anySatisfy(event ->
                assertThat(event).isInstanceOf(NewOrganizationEvent.class)
                        .extracting("organizationId", "eventType")
                        .containsExactly(42L, ORG_MEMBER_ADDED));
    }

    @Test
    @DisplayName("a first-time org-saml login auto-joins the organization exactly like org-oidc")
    void autoJoinsAndAuditsOnFirstTimeSamlLogin() throws Exception {
        when(organizationService.ensureAutoJoinMembership(42L, 99L)).thenReturn(true);

        service.completeLogin("org-saml-42", activeUser(99L), request, response);

        verify(organizationService).ensureAutoJoinMembership(42L, 99L);
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).anySatisfy(event ->
                assertThat(event).isInstanceOf(NewOrganizationEvent.class)
                        .extracting("organizationId", "eventType")
                        .containsExactly(42L, ORG_MEMBER_ADDED));
    }

    @Test
    @DisplayName("a returning already-active member is never re-added and no ORG_MEMBER_ADDED audit fires")
    void doesNotReJoinOrReauditAnExistingMember() throws Exception {
        when(organizationService.ensureAutoJoinMembership(42L, 99L)).thenReturn(false);

        service.completeLogin("org-oidc-42", activeUser(99L), request, response);

        verify(organizationService).ensureAutoJoinMembership(42L, 99L);
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).noneMatch(NewOrganizationEvent.class::isInstance);
    }

    @Test
    @DisplayName("an ordinary consumer-provider login never consults the organization service at all")
    void ordinaryProviderLoginNeverTouchesOrganizationService() throws Exception {
        service.completeLogin("google", activeUser(7L), request, response);

        verify(organizationService, never()).ensureAutoJoinMembership(any(), any());
    }

    @Test
    @DisplayName("a disabled account is refused before any tokens are issued")
    void refusesDisabledAccount() throws Exception {
        UserDTO disabled = activeUser(1L);
        disabled.setEnabled(false);

        service.completeLogin("google", disabled, request, response);

        verify(response).sendRedirect("http://localhost:4200/login?error=account");
        verify(sessionService, never()).issueTokenPair(any(), any());
    }

    @Test
    @DisplayName("a locked account is refused before any tokens are issued")
    void refusesLockedAccount() throws Exception {
        UserDTO locked = activeUser(1L);
        locked.setNotLocked(false);

        service.completeLogin("google", locked, request, response);

        verify(response).sendRedirect("http://localhost:4200/login?error=account");
        verify(sessionService, never()).issueTokenPair(any(), any());
    }

    @Test
    @DisplayName("a TOTP-enrolled user is redirected to the authenticator challenge instead of receiving tokens")
    void redirectsToTotpChallenge() throws Exception {
        UserDTO totpUser = activeUser(5L);
        totpUser.setUsingTotp(true);
        when(totpService.createLoginChallenge(5L)).thenReturn("challenge-token");

        service.completeLogin("google", totpUser, request, response);

        verify(response).sendRedirect("http://localhost:4200/oauth2/callback#mfa=totp&challenge=challenge-token");
        verify(sessionService, never()).issueTokenPair(any(), any());
    }

    @Test
    @DisplayName("a 2FA-enrolled user is sent an SMS challenge and redirected to the MFA screen")
    void redirectsToSmsChallenge() throws Exception {
        UserDTO smsUser = activeUser(6L);
        smsUser.setUsing2FA(true);
        smsUser.setPhoneNumber("+15551234567");

        service.completeLogin("google", smsUser, request, response);

        verify(userService).sendVerificationCode(smsUser);
        verify(response).sendRedirect(
                "http://localhost:4200/oauth2/callback#mfa=true&email=member%40acme.com&phone=%2B15551234567");
        verify(sessionService, never()).issueTokenPair(any(), any());
    }

    @Test
    @DisplayName("a duplicate SMS dispatch within the debounce window is suppressed")
    void suppressesDuplicateSmsWithinDebounceWindow() throws Exception {
        UserDTO smsUser = activeUser(6L);
        smsUser.setUsing2FA(true);
        smsUser.setPhoneNumber("+15551234567");

        service.completeLogin("google", smsUser, request, response);
        service.completeLogin("google", smsUser, request, response);

        verify(userService, org.mockito.Mockito.times(1)).sendVerificationCode(smsUser);
    }

    @Test
    @DisplayName("a normal login issues tokens and redirects to the SPA callback")
    void issuesTokensForNormalLogin() throws Exception {
        service.completeLogin("google", activeUser(7L), request, response);

        verify(sessionService).issueTokenPair(any(), any());
        verify(response).sendRedirect("http://localhost:4200/oauth2/callback#access_token=access-token&refresh_token=refresh-token");
    }
}
