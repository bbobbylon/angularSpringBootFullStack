package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.controller.FederatedAuthController;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
import com.bob.angularspringbootfullstack.service.FederatedLoginCompletionService;
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

import static com.bob.angularspringbootfullstack.constants.Constants.ORG_OIDC_REGISTRATION_PREFIX;
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
 *   <li>hand off to {@link FederatedLoginCompletionService} for everything after that —
 *       auto-join, account-state/MFA policy, token issuance, and the SPA redirect — the
 *       protocol-agnostic tail shared with {@code OrgSamlLoginSuccessHandler} (Stage 3).</li>
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
    private final ApplicationEventPublisher eventPublisher;
    private final FederatedLoginCompletionService federatedLoginCompletionService;

    /**
     * The SPA origin (env {@code UI_APP_URL}); the account-link outcome redirect (the one path this
     * class still issues directly, rather than through {@link FederatedLoginCompletionService}) lands
     * on a route served by the Angular app, mirroring how every other redirect in this application is
     * built.
     */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Completes a federated login per the class contract: resolve the local user, then delegate to
     * {@link FederatedLoginCompletionService} for account policy, MFA, and token issuance.
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

            federatedLoginCompletionService.completeLogin(provider, userDTO, request, response);
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
     *
     * <p>An {@code org-oidc-*} provider (Stage 2's per-organization external IdP) is handled before
     * the three-way switch below rather than as a fourth case, since its registration id is not one
     * fixed literal but {@code org-oidc-{organizationId}} for any organization — and, unlike the
     * three hand-rolled consumer providers, it can lean on generic OIDC standard claims instead of a
     * provider-specific attribute mapping: every registration built by
     * {@code OrgAwareClientRegistrationRepository} came from a real {@code .well-known/openid-
     * configuration} discovery document, so {@code sub}/{@code email}/{@code given_name}/
     * {@code family_name}/{@code picture} are guaranteed to mean what OIDC says they mean.
     */
    private FederatedProfile extractProfile(String provider, OAuth2User principal) {
        if (provider.startsWith(ORG_OIDC_REGISTRATION_PREFIX)) {
            return new FederatedProfile(
                    principal.getName(),
                    principal.getAttribute("email"),
                    attributeOr(principal, "given_name", "Organization"),
                    attributeOr(principal, "family_name", "Member"),
                    principal.getAttribute("picture"));
        }
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

    private record FederatedProfile(String subject, String email, String firstName, String lastName, String imageUrl) {
    }
}
