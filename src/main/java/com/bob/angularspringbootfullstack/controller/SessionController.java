package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bob.angularspringbootfullstack.constants.Constants.TOKEN_PREFIX;
import static com.bob.angularspringbootfullstack.enumeration.EventType.SESSION_REVOKED;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.OK;

/**
 * REST endpoints for the Account Security Center's sessions & devices panel
 * (plan.md M5, SRS FR-JWT-5's user-visible half): list the caller's live refresh
 * sessions and revoke them individually or en masse.
 *
 * <p>Authorization posture: all routes are matched by the explicit
 * {@code /user/sessions/**  authenticated()} rule in {@code SecurityConfig} (placed
 * before the authority-gated catch-alls), because viewing and revoking one's OWN
 * sessions must not require staff authorities. Every operation is scoped to the
 * token's principal — the service folds ownership into the SQL predicates.
 *
 * <p>"Current session" detection: each access token carries its session family in the
 * {@code sid} claim (see {@code TokenProvider#createAccessToken}). The list endpoint
 * echoes that family back as {@code currentFamily} so the SPA can badge the row the
 * caller is sitting on and exclude it from "log out everywhere else".
 */
@RestController
@RequestMapping(path = "/user/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionController {

    private final SessionService sessionService;
    private final TokenProvider tokenProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpServletRequest request;

    /**
     * Lists the caller's live sessions (one per family, newest activity first) plus
     * the family of the session this very request rides on.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with {@code sessions} and {@code currentFamily}
     */
    @GetMapping
    public ResponseEntity<HttpResponse> listSessions(Authentication authentication) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("sessions", sessionService.listSessions(userDTO.getId()),
                                "currentFamily", currentFamilyOrEmpty()))
                        .message("Active sessions retrieved.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Revokes one of the caller's sessions. The revoked family can never refresh again;
     * its outstanding access token (if any) ages out within its 30-minute TTL — the
     * accepted trade for keeping access-token validation database-free (NFR-PERF-2).
     *
     * @param authentication the current Spring Security authentication
     * @param family         the session family to revoke (from the list response)
     * @return 200 OK with the refreshed {@code sessions} list and {@code currentFamily}
     */
    @DeleteMapping("/{family}")
    public ResponseEntity<HttpResponse> revokeSession(Authentication authentication, @PathVariable String family) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        sessionService.revokeSession(userDTO.getId(), family);
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), SESSION_REVOKED));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("sessions", sessionService.listSessions(userDTO.getId()),
                                "currentFamily", currentFamilyOrEmpty()))
                        .message("Session revoked.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * "Log out everywhere else": revokes every session except the one this request
     * rides on, so the user keeps working while every other device is cut off.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with the refreshed {@code sessions} list and {@code currentFamily}
     */
    @DeleteMapping
    public ResponseEntity<HttpResponse> revokeOtherSessions(Authentication authentication) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        int revoked = sessionService.revokeOtherSessions(userDTO.getId(), currentFamilyOrEmpty());
        if (revoked > 0) {
            eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), SESSION_REVOKED));
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("sessions", sessionService.listSessions(userDTO.getId()),
                                "currentFamily", currentFamilyOrEmpty()))
                        .message(revoked > 0
                                ? "Logged out of " + revoked + " other session(s)."
                                : "No other active sessions to log out of.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Ends <em>this</em> session server-side — the missing half of "log out".
     *
     * <p>Signing out previously cleared the SPA's {@code localStorage} and told the server
     * nothing, so the refresh session stayed live for its full five days. Two consequences
     * followed. A token captured before sign-out kept working, because logging out revoked
     * nothing it could not simply keep using. And the Security Center's device list filled with
     * one live entry per past login, which made the panel that exists to answer "where am I
     * signed in?" unable to answer it — four rows for one laptop is noise, not information.
     *
     * <p>Revokes only the caller's own family, so other devices stay signed in — that is the
     * distinction from {@link #revokeOtherSessions}, and the reason this is not simply
     * "revoke everything". A caller whose token carries no family (pre-M5) has no session row to
     * revoke; the call still succeeds, because a client that has already discarded its tokens is
     * logged out either way and an error here would be about nothing it can act on.
     *
     * <p>The access token itself is not invalidated — it is stateless and expires on its own
     * within 30 minutes. What this stops is the ability to <em>renew</em>, which is what turns a
     * stolen refresh token into indefinite access.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK confirming the session was ended
     */
    @PostMapping("/logout")
    public ResponseEntity<HttpResponse> logout(Authentication authentication) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        String family = currentFamilyOrEmpty();
        if (!family.isEmpty()) {
            sessionService.revokeSession(userDTO.getId(), family);
            eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), SESSION_REVOKED));
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("You have been signed out.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Extracts the {@code sid} (session family) claim from this request's own Bearer
     * token. Empty string for pre-M5 tokens that carry no family — such callers see
     * their list without a "current" badge, and "log out everywhere else" revokes
     * everything (their token rides no listed session anyway).
     */
    private String currentFamilyOrEmpty() {
        String header = request.getHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(TOKEN_PREFIX)) return "";
        try {
            String family = tokenProvider.getSessionFamily(header.substring(TOKEN_PREFIX.length()));
            return family == null ? "" : family;
        } catch (Exception exception) {
            // The filter already authenticated this token; a claim hiccup only loses the badge.
            log.debug("Could not extract session family from access token: {}", exception.getMessage());
            return "";
        }
    }
}
