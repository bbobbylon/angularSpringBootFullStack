package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.controller.FederatedAuthController;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import static com.bob.angularspringbootfullstack.enumeration.EventType.PROVIDER_LINKED;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.FEDERATED_LOGIN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * The token-exchange point for federated sign-in (SRS §1.4, FR-FED-2..5).
 *
 * <p>Spring Security's OAuth2 client has already completed the Authorization Code flow
 * by the time this handler runs: the provider redirect was validated, the code was
 * exchanged, and (for OIDC providers) the identity token was verified. What remains —
 * and what this class owns — is converting that verified external identity into an
 * application session:
 *
 * <ol>
 *   <li>extract the provider's stable subject identifier and profile attributes
 *       (FR-FED-2), with per-provider attribute handling since Google, GitHub, and
 *       Microsoft each shape their userinfo differently;</li>
 *   <li>find-or-create the local user via {@link FederatedIdentityService}
 *       (FR-FED-3);</li>
 *   <li>apply the SAME account policies as in-house login: disabled/locked accounts
 *       are refused (FR-AUTH-5 parity), and MFA-enabled accounts are sent an SMS code
 *       and must complete the second factor before any token is issued (FR-MFA-2);</li>
 *   <li>issue the application's own access/refresh JWTs (FR-FED-4) and record a
 *       FEDERATED_LOGIN audit event (FR-FED-5);</li>
 *   <li>hand the tokens to the SPA by redirecting to its {@code /oauth2/callback}
 *       route with the tokens in the URL <em>fragment</em> — fragments never leave the
 *       browser (not sent in requests, absent from server/proxy logs), which is why
 *       they are preferred over query parameters for token transport.</li>
 * </ol>
 *
 * <p>Every failure path degrades to a redirect onto the SPA login screen with a coarse
 * {@code error} code — never an exception page, and never an error detail that could
 * disclose whether an account exists (NFR-SEC-7).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final FederatedIdentityService federatedIdentityService;
    private final UserService userService;
    private final RoleService roleService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final TotpService totpService;

    /**
     * The SPA origin (env {@code UI_APP_URL}); all post-login redirects land on routes
     * served by the Angular app, mirroring how email verification links are built.
     */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Debounce window for {@link #sendSmsChallengeOnce}, guarding against a duplicate Twilio
     * dispatch (and a real charge) when this handler runs twice in quick succession for the same
     * user — e.g. a provider/proxy-level retry of the {@code /login/oauth2/code/{provider}}
     * callback, or the caller re-attempting "Sign in with Google" after the previous attempt
     * appeared to hang. Every {@code onAuthenticationSuccess} invocation issues a brand new
     * OAuth2 authentication, so this cannot be keyed off anything provider-supplied (state/code are
     * already consumed by the time this handler runs) — the local user id plus a short wall-clock
     * window is the only signal available here.
     */
    private static final Duration SMS_CHALLENGE_DEBOUNCE = Duration.ofSeconds(15);

    /** Per-user last-dispatch timestamp backing {@link #sendSmsChallengeOnce}. */
    private final Map<Long, Instant> lastSmsChallengeAt = new ConcurrentHashMap<>();

    /**
     * Completes a federated login per the class contract: resolve the local user,
     * enforce account state and MFA policy, then deliver tokens (or the MFA challenge)
     * to the SPA via redirect.
     *
     * @param request        the callback request from the provider redirect
     * @param response       the response used for the redirect to the SPA
     * @param authentication the OAuth2 authentication built by Spring Security
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            String provider = oauthToken.getAuthorizedClientRegistrationId();
            FederatedProfile profile = extractProfile(provider, oauthToken.getPrincipal());

            // ── Account-link handshake (ROADMAP §1.4) ────────────────────────────────────────
            // A link intent parked by FederatedAuthController#startLink means the user was ALREADY
            // signed in and asked to attach this identity to their existing account. That is a
            // different question from "who is this identity?", so it must not go through
            // find-or-create: doing so is what used to switch the session to whichever account the
            // provider identity happened to resolve to. No tokens are issued here — the caller's
            // existing session simply continues.
            Long linkUserId = consumeLinkIntent(request);
            if (linkUserId != null) {
                handleAccountLink(response, provider, profile, linkUserId);
                return;
            }

            UserDTO userDTO = federatedIdentityService.findOrCreateFederatedUser(
                    provider, profile.subject(), profile.email(), profile.firstName(), profile.lastName(), profile.imageUrl());

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

            // FR-FED-5: record WHICH provider authenticated the user (google | github | microsoft)
            // on the audit row itself, not just in the server log — the detail lands in userevents.detail.
            eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), FEDERATED_LOGIN, provider));
            UserPrincipal principal = new UserPrincipal(toUser(userDTO), roleService.getRoleByUserId(userDTO.getId()));
            // SessionService (plan.md M5) opens a tracked, revocable session — federated
            // logins appear in the Security Center device list like in-house ones (FR-FED-4).
            SessionService.TokenPair tokens = sessionService.issueTokenPair(principal, request);
            response.sendRedirect(uiAppUrl + "/oauth2/callback"
                    + "#access_token=" + URLEncoder.encode(tokens.accessToken(), UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(tokens.refreshToken(), UTF_8));
        } catch (Exception exception) {
            // Coarse error code only: the SPA shows a generic failure message, and nothing
            // in the redirect reveals whether an account exists (NFR-SEC-7).
            log.error("Federated login post-processing failed: {}", exception.getMessage(), exception);
            response.sendRedirect(uiAppUrl + "/login?error=federated");
        }
    }

    /**
     * Normalizes provider-specific userinfo attributes into one profile shape
     * (FR-FED-2). Google and Microsoft are OIDC, so the stable subject is the
     * {@code sub} claim; GitHub is plain OAuth2 and uses its numeric account
     * {@code id}. GitHub may also withhold the email when the user's address is
     * private — a deterministic {@code @users.noreply.github.com} address is
     * synthesized so account rows stay valid; the durable identity key is always
     * (provider, subject), never the email.
     */
    private FederatedProfile extractProfile(String provider, OAuth2User principal) {
        return switch (provider) {
            case "google" -> new FederatedProfile(
                    principal.getName(),
                    principal.getAttribute("email"),
                    attributeOr(principal, "given_name", "Google"),
                    attributeOr(principal, "family_name", "User"),
                    principal.getAttribute("picture"));
            case "github" -> {
                String subject = String.valueOf((Object) principal.getAttribute("id"));
                String login = attributeOr(principal, "login", "github-user");
                String email = principal.getAttribute("email");
                String[] names = splitName(principal.getAttribute("name"), login);
                yield new FederatedProfile(
                        subject,
                        isBlank(email) ? login + "@users.noreply.github.com" : email,
                        names[0], names[1],
                        principal.getAttribute("avatar_url"));
            }
            case "microsoft" -> {
                String email = principal.getAttribute("email");
                if (isBlank(email)) email = principal.getAttribute("preferred_username");
                String[] names = splitName(principal.getAttribute("name"), "Microsoft User");
                yield new FederatedProfile(principal.getName(), email, names[0], names[1], null);
            }
            default -> throw new IllegalStateException("Unsupported federated provider: " + provider);
        };
    }

    /** Returns the attribute when present and non-blank, otherwise the fallback. */
    private static String attributeOr(OAuth2User principal, String attribute, String fallback) {
        String value = principal.getAttribute(attribute);
        return isBlank(value) ? fallback : value;
    }

    /**
     * Splits a display name into first/last on the first space, since the local schema
     * requires both. Single-token and missing names fall back to (name-or-fallback, "•")
     * — the placeholder keeps the NOT NULL constraint satisfied without inventing data.
     */
    private static String[] splitName(String fullName, String fallback) {
        String source = isBlank(fullName) ? fallback : fullName.trim();
        int space = source.indexOf(' ');
        if (space < 0) return new String[]{source, "•"};
        return new String[]{source.substring(0, space), source.substring(space + 1)};
    }

    /**
     * Provider-neutral identity snapshot: the stable subject plus the best-effort
     * profile fields the local account needs at creation time (FR-FED-6 — nothing more
     * than this is ever persisted from the provider).
     */
    /**
     * Reads and clears any pending link intent for this handshake.
     *
     * <p>Cleared unconditionally, whether or not it is used: an intent that survived into a later,
     * unrelated sign-in would silently attach that identity to the earlier user's account.
     *
     * @param request the callback request, whose session may carry the intent
     * @return the user id to link to, or null for an ordinary sign-in
     */
    private static Long consumeLinkIntent(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute(FederatedAuthController.LINK_INTENT_SESSION_KEY);
        session.removeAttribute(FederatedAuthController.LINK_INTENT_SESSION_KEY);
        return value instanceof Long userId ? userId : null;
    }

    /**
     * Attaches the verified identity to the account that asked for it and returns the browser to the
     * Security Center.
     *
     * <p>Redirects rather than issuing tokens: the user never stopped being signed in, and minting a
     * fresh session here would be the very behavior this branch exists to prevent. The outcome is
     * reported through a query flag so the SPA can raise the right toast — including the refusal,
     * which is a normal thing to hit (connecting an identity that already belongs to someone else)
     * rather than an error state.
     *
     * @param response the response used for the redirect
     * @param provider the registration id being connected
     * @param profile  the verified provider identity
     * @param userId   the account the link was requested for
     */
    private void handleAccountLink(HttpServletResponse response, String provider,
                                   FederatedProfile profile, Long userId) throws IOException {
        try {
            boolean linked = federatedIdentityService.linkProviderToUser(userId, provider, profile.subject());
            if (linked) {
                UserDTO owner = userService.getUserById(userId);
                eventPublisher.publishEvent(new NewUserEvent(owner.getEmail(), PROVIDER_LINKED, provider));
            }
            response.sendRedirect(uiAppUrl + "/security?linked=" + URLEncoder.encode(provider, UTF_8));
        } catch (ApiException exception) {
            log.warn("Federated link refused for userId {} provider '{}': {}", userId, provider, exception.getMessage());
            response.sendRedirect(uiAppUrl + "/security?linkError=" + URLEncoder.encode(exception.getMessage(), UTF_8));
        }
    }

    /**
     * Dispatches the SMS/voice 2FA challenge via {@link UserService#sendVerificationCode}, unless
     * one was already dispatched for this exact user within {@link #SMS_CHALLENGE_DEBOUNCE} — see
     * that field's Javadoc for why a duplicate call here is a real risk despite there being only one
     * call site. Skipping the resend is safe either way: a code issued moments ago is still valid
     * and still pending, so the redirect to the MFA screen below proceeds unchanged regardless of
     * which branch runs.
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

    private record FederatedProfile(String subject, String email, String firstName, String lastName, String imageUrl) {
    }
}
