package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.configuration.FederatedProviderCatalog;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.servlet.http.HttpServletRequest;
import com.bob.angularspringbootfullstack.service.serviceimpl.ProviderLinkTicketService;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Public discovery endpoint for federated login (supports EIR-UI-1's "federated login
 * entry points").
 *
 * <p>{@code GET /oauth2/providers} tells the Angular login screen which identity
 * providers are configured in this environment, so it renders a button only for flows
 * that can actually complete. The path lives under the {@code /oauth2/**} public prefix
 * (Constants.PUBLIC_URLS) and does not collide with Spring Security's own
 * {@code /oauth2/authorization/{registrationId}} initiation endpoints.
 *
 * <p>Anti-enumeration note (NFR-SEC-7): this endpoint discloses only deployment
 * configuration (which providers exist), never anything about user accounts.
 */
@RestController
@RequestMapping(path = "/oauth2")
@RequiredArgsConstructor
@Slf4j
public class FederatedAuthController {

    private final FederatedProviderCatalog catalog;
    private final ProviderLinkTicketService linkTicketService;

    /**
     * Lists the configured federated provider ids in render order.
     *
     * @return 200 OK with {@code providers}: e.g. {@code ["google","github"]}; an empty
     *         list when federated login is not configured in this environment
     */
    /**
     * Key under which a pending link intent is parked in the HTTP session.
     *
     * <p>Read back by {@code OAuth2LoginSuccessHandler} on the provider callback. The session is
     * already in play at that point — Spring Security's default authorization-request repository
     * uses it to hold the CSRF {@code state} between the outbound redirect and the callback — so
     * this adds no statefulness the OAuth handshake did not already require, and none that outlives
     * it.
     */
    public static final String LINK_INTENT_SESSION_KEY = "federated.link.userId";

    /**
     * Begins an account-link handshake: validates a single-use ticket, records the intent, and
     * hands off to the ordinary Spring Security authorization endpoint (ROADMAP §1.4).
     *
     * <p><b>Public by necessity, safe by construction.</b> This is reached by a top-level browser
     * navigation, which cannot carry an {@code Authorization} header, so it cannot require a JWT.
     * The ticket is what stands in: opaque, single-use, five-minute, minted only for an authenticated
     * caller, and worthless on its own — redeeming one authenticates nobody. It only decides which
     * local account a subsequently-verified provider identity should attach to, and that attachment
     * still has to pass the "already linked to another account" refusal.
     *
     * <p>An invalid or expired ticket does not error into the user's face: it redirects into the
     * plain login flow, which is the sensible degraded behavior for someone who took too long on a
     * consent screen.
     *
     * @param provider the registration id being connected
     * @param ticket   the value returned by {@code POST /user/sessions/providers/link/{provider}}
     * @param request  the current request, used to obtain the session that carries the intent
     * @return a redirect into {@code /oauth2/authorization/{provider}}
     */
    @GetMapping("/link/{provider}")
    public RedirectView startLink(@PathVariable String provider,
                                  @RequestParam(required = false) String ticket,
                                  HttpServletRequest request) {
        linkTicketService.redeem(ticket, provider).ifPresentOrElse(
                userId -> {
                    request.getSession(true).setAttribute(LINK_INTENT_SESSION_KEY, userId);
                    log.info("[FEDERATION] Link intent recorded for userId={} provider={}", userId, provider);
                },
                () -> {
                    // Stale or forged ticket: clear any leftover intent so this handshake cannot
                    // inherit one from an earlier attempt, then continue as an ordinary login.
                    request.getSession(true).removeAttribute(LINK_INTENT_SESSION_KEY);
                    log.debug("[FEDERATION] No valid link ticket for provider={} — continuing as a normal sign-in", provider);
                });
        return new RedirectView("/oauth2/authorization/" + provider);
    }

    @GetMapping("/providers")
    public ResponseEntity<HttpResponse> providers() {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("providers", catalog.getProviders()))
                        .message("Federated providers retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
