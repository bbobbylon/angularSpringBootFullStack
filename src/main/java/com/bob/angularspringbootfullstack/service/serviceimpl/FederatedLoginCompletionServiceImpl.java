package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.FederatedLoginCompletionService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.bob.angularspringbootfullstack.constants.Constants.ORG_OIDC_REGISTRATION_PREFIX;
import static com.bob.angularspringbootfullstack.constants.Constants.ORG_SAML_REGISTRATION_PREFIX;
import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.FEDERATED_LOGIN;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_MEMBER_ADDED;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * {@inheritDoc}
 *
 * <p>Moved out of {@code OAuth2LoginSuccessHandler} when SAML support (Stage 3) needed the identical
 * sequence — see that interface's Javadoc for why this is a single shared class rather than one
 * per protocol.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FederatedLoginCompletionServiceImpl implements FederatedLoginCompletionService {

    private final OrganizationService organizationService;
    private final RoleService roleService;
    private final SessionService sessionService;
    private final TotpService totpService;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * The SPA origin (env {@code UI_APP_URL}); all post-login redirects land on routes served by
     * the Angular app. {@code /oauth2/callback} is reused as the landing route for every federated
     * protocol, SAML included — it predates SAML support but is simply the SPA's "a federated login
     * just finished" route, not something tied to the OAuth2 protocol name specifically, so renaming
     * it for SAML would be a purely cosmetic frontend change with no behavioral benefit.
     */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Debounce window guarding against a duplicate Twilio SMS dispatch (and a real charge) when this
     * method runs twice in quick succession for the same user — see
     * {@code OAuth2LoginSuccessHandler}'s former field of the same purpose for the full rationale,
     * which applies identically regardless of which protocol triggered the duplicate call.
     */
    private static final Duration SMS_CHALLENGE_DEBOUNCE = Duration.ofSeconds(15);

    /** Per-user last-dispatch timestamp backing {@link #sendSmsChallengeOnce}. */
    private final Map<Long, Instant> lastSmsChallengeAt = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void completeLogin(String provider, UserDTO userDTO, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ensureOrgMembershipIfSsoLogin(provider, userDTO);

        // FR-AUTH-5 parity: federated sign-in must not become a side door around
        // administrative disable/lock decisions on the LOCAL account.
        if (!userDTO.isEnabled() || !userDTO.isNotLocked()) {
            log.warn("Federated login refused for disabled/locked account id {}", userDTO.getId());
            response.sendRedirect(uiAppUrl + "/login?error=account");
            return;
        }

        // FR-MFA-2/4: a successful first factor (federated included) does not yield
        // tokens while MFA is enabled. TOTP takes precedence over SMS, mirroring the
        // password login path: mint the server-side challenge and bounce the browser
        // into the login screen's authenticator-code state.
        if (userDTO.isUsingTotp()) {
            String challenge = totpService.createLoginChallenge(userDTO.getId());
            response.sendRedirect(uiAppUrl + "/oauth2/callback#mfa=totp"
                    + "&challenge=" + URLEncoder.encode(challenge, UTF_8));
            return;
        }

        // FR-MFA-2: a successful first factor (federated included) does not yield tokens
        // while MFA is enabled — send the SMS code and bounce to the SPA's MFA screen.
        if (userDTO.isUsing2FA()) {
            sendSmsChallengeOnce(userDTO);
            String phone = userDTO.getPhoneNumber() == null ? "" : userDTO.getPhoneNumber();
            response.sendRedirect(uiAppUrl + "/oauth2/callback#mfa=true"
                    + "&email=" + URLEncoder.encode(userDTO.getEmail(), UTF_8)
                    + "&phone=" + URLEncoder.encode(phone, UTF_8));
            return;
        }

        // FR-FED-5: record WHICH provider authenticated the user on the audit row itself,
        // not just in the server log — the detail lands in userevents.detail.
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), FEDERATED_LOGIN, provider));
        UserPrincipal principal = new UserPrincipal(toUser(userDTO), roleService.getRoleByUserId(userDTO.getId()));
        // SessionService (plan.md M5) opens a tracked, revocable session — federated
        // logins appear in the Security Center device list like in-house ones (FR-FED-4).
        SessionService.TokenPair tokens = sessionService.issueTokenPair(principal, request);
        response.sendRedirect(uiAppUrl + "/oauth2/callback"
                + "#access_token=" + URLEncoder.encode(tokens.accessToken(), UTF_8)
                + "&refresh_token=" + URLEncoder.encode(tokens.refreshToken(), UTF_8));
    }

    /**
     * Auto-joins the user to the organization an {@code org-oidc-*}/{@code org-saml-*} login
     * authenticated against (FUTURE-ENHANCEMENTS.md §3.1) — a no-op for every consumer provider.
     *
     * <p>Delegates the actual gating to {@link OrganizationService#ensureAutoJoinMembership}, which
     * refuses to touch an already-active membership; the {@code ORG_MEMBER_ADDED} audit event is
     * published here, not inside that method, only when it reports a genuinely new join — otherwise
     * every returning SSO login would write a fresh audit row for a membership that already existed.
     *
     * @param provider the resolved registration id, e.g. {@code "google"}, {@code "org-oidc-42"}, or
     *                 {@code "org-saml-42"}
     * @param userDTO  the local user the login just resolved to
     */
    private void ensureOrgMembershipIfSsoLogin(String provider, UserDTO userDTO) {
        boolean isOrgSso = provider.startsWith(ORG_OIDC_REGISTRATION_PREFIX) || provider.startsWith(ORG_SAML_REGISTRATION_PREFIX);
        if (!isOrgSso) {
            return;
        }
        Long organizationId = parseOrganizationId(provider);
        if (organizationId == null) {
            log.warn("Could not recover an organization id from SSO registration id '{}'", provider);
            return;
        }
        boolean joined = organizationService.ensureAutoJoinMembership(organizationId, userDTO.getId());
        if (joined) {
            log.info("Auto-joined user {} to organization {} via SSO", userDTO.getId(), organizationId);
            eventPublisher.publishEvent(new NewOrganizationEvent(
                    organizationId, userDTO.getId(), ORG_MEMBER_ADDED, "user " + userDTO.getId() + " (auto-joined via SSO)"));
        }
    }

    /** Recovers the organization id from an {@code org-oidc-{id}}/{@code org-saml-{id}} registration id. */
    private static Long parseOrganizationId(String provider) {
        String prefix = provider.startsWith(ORG_OIDC_REGISTRATION_PREFIX) ? ORG_OIDC_REGISTRATION_PREFIX : ORG_SAML_REGISTRATION_PREFIX;
        try {
            return Long.parseLong(provider.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Dispatches the SMS/voice 2FA challenge via {@link UserService#sendVerificationCode}, unless
     * one was already dispatched for this exact user within {@link #SMS_CHALLENGE_DEBOUNCE}. Skipping
     * the resend is safe either way: a code issued moments ago is still valid and still pending, so
     * the redirect to the MFA screen proceeds unchanged regardless of which branch runs.
     *
     * @param userDTO the federated user whose phone challenge is being started
     */
    private void sendSmsChallengeOnce(UserDTO userDTO) {
        Instant now = Instant.now();
        Instant previous = lastSmsChallengeAt.put(userDTO.getId(), now);
        if (previous != null && Duration.between(previous, now).compareTo(SMS_CHALLENGE_DEBOUNCE) < 0) {
            log.warn("Suppressed duplicate SMS 2FA dispatch for user id {} — a challenge was already sent {}ms ago",
                    userDTO.getId(), Duration.between(previous, now).toMillis());
            return;
        }
        userService.sendVerificationCode(userDTO);
    }
}
