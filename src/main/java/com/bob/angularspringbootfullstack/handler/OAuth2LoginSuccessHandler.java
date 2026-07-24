package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;

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
                userService.sendVerificationCode(userDTO);
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
    private record FederatedProfile(String subject, String email, String firstName, String lastName, String imageUrl) {
    }
}
