package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.form.PasskeyLoginVerifyForm;
import com.bob.angularspringbootfullstack.form.PasskeyRegisterVerifyForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.PasskeyService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.PASSKEY_LOGIN;
import static com.bob.angularspringbootfullstack.enumeration.EventType.PASSKEY_REGISTERED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.PASSKEY_REMOVED;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * REST endpoints for passkeys (WebAuthn) — the Account Security Center's passkey lifecycle plus
 * usernameless sign-in. Mirrors {@link TotpController}'s split of authorization postures exactly:
 *
 * <ul>
 *   <li>{@code /user/webauthn/*} lifecycle endpoints require an authenticated session — matched by
 *       {@code SecurityConfig}'s {@code authenticated()} rule ahead of the broad catch-alls, same
 *       reasoning as {@code /user/totp/**}: securing one's own account must never demand staff
 *       authorities.</li>
 *   <li>{@code POST /user/verify/webauthn/options} and {@code POST /user/verify/webauthn} are public
 *       ({@code PUBLIC_URLS}/{@code PUBLIC_ROUTES}) because the caller is mid-login and holds no
 *       token yet — the WebAuthn assertion signature is the security boundary, not a bearer token.
 *       {@code POST /user/webauthn/enroll/options} is intentionally NOT public: minting a
 *       registration challenge always requires an authenticated caller, unlike login. Named
 *       "enroll", not "register" — the frontend's {@code tokenInterceptor} withholds the
 *       Authorization header from any URL with {@code register} as a path segment, which this
 *       endpoint needs.</li>
 * </ul>
 *
 * <p>Passkey login deliberately skips {@code LoginRiskService}'s step-up entirely — the same
 * treatment {@code OAuth2LoginSuccessHandler} already gives federated login. A passkey is
 * phishing-resistant and bound to a specific device/authenticator; stacking a TOTP or emailed-code
 * challenge on top of it would add friction without adding security.
 */
@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
@Slf4j
public class PasskeyController {

    private final PasskeyService passkeyService;
    private final UserService userService;
    private final RoleService roleService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpServletRequest request;

    /**
     * Begins registering a new passkey for the signed-in user: mints a challenge and returns
     * creation options for {@code navigator.credentials.create()}.
     *
     * <p><b>Why "enroll", not "register".</b> The frontend's {@code tokenInterceptor} withholds the
     * Authorization header from any request whose URL has {@code register} (or {@code verify}) as an
     * exact path <em>segment</em> — that convention is what makes {@code /user/register} and
     * {@code /user/verify/webauthn/*} correctly go out with no token. This endpoint is the opposite
     * case (it REQUIRES a token), so its path must avoid every one of those reserved segments or the
     * interceptor silently strips the header and the call 401s. Learned the hard way: the first cut
     * of this endpoint was literally {@code /webauthn/register/options} and did exactly that.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with the {@code publicKey} creation options
     */
    @PostMapping("/webauthn/enroll/options")
    public ResponseEntity<HttpResponse> registerOptions(Authentication authentication) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        PasskeyService.CeremonyOptions options = passkeyService.beginRegistration(userDTO.getId(), userDTO.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("publicKey", options.publicKey()))
                        .message("Follow your device's prompt to create a passkey.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Completes passkey registration: verifies the browser's response and persists the credential.
     * Publishes a PASSKEY_REGISTERED audit event.
     *
     * @param authentication the current Spring Security authentication
     * @param form           the validated body carrying the device nickname and credential response
     * @return 200 OK with the refreshed user
     */
    @PostMapping("/webauthn/enroll/complete")
    public ResponseEntity<HttpResponse> registerVerify(Authentication authentication, @RequestBody @Valid PasskeyRegisterVerifyForm form) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        passkeyService.finishRegistration(userDTO.getId(), form.getDeviceName(), form.getCredential().toString());
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), PASSKEY_REGISTERED));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserById(userDTO.getId()),
                                "passkeys", passkeyService.listCredentials(userDTO.getId())))
                        .message("Passkey added.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Lists the signed-in user's registered passkeys for the Security Center.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with the {@code passkeys} list
     */
    @GetMapping("/webauthn/list")
    public ResponseEntity<HttpResponse> list(Authentication authentication) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("passkeys", passkeyService.listCredentials(userDTO.getId())))
                        .message("Passkeys retrieved.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Removes one of the signed-in user's own passkeys. Publishes a PASSKEY_REMOVED audit event.
     *
     * @param authentication the current Spring Security authentication
     * @param id             the credential's primary key (never the WebAuthn credential id itself)
     * @return 200 OK with the refreshed passkey list
     */
    @DeleteMapping("/webauthn/{id}")
    public ResponseEntity<HttpResponse> delete(Authentication authentication, @PathVariable Long id) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        passkeyService.deleteCredential(userDTO.getId(), id);
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), PASSKEY_REMOVED));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("passkeys", passkeyService.listCredentials(userDTO.getId())))
                        .message("Passkey removed.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Public login-start endpoint: mints a usernameless authentication challenge. No account is
     * identified here — that is the entire point of a discoverable-credential login.
     *
     * @return 200 OK with the {@code publicKey} request options
     */
    @PostMapping("/verify/webauthn/options")
    public ResponseEntity<HttpResponse> authenticateOptions() {
        PasskeyService.CeremonyOptions options = passkeyService.beginAuthentication();
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("publicKey", options.publicKey()))
                        .message("Choose a passkey to sign in.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Public login-completion endpoint: verifies the assertion, resolves the account, and mints
     * tokens directly — no risk-based step-up and no second factor, per this controller's class-level
     * doc. Publishes PASSKEY_LOGIN (not LOGIN_ATTEMPT_SUCCESS) so the audit trail records which
     * authentication method was used, mirroring {@code FEDERATED_LOGIN}.
     *
     * @param form the validated body carrying the browser's authentication response
     * @return 200 OK with the user and {@code access_token}/{@code refresh_token}
     */
    @PostMapping("/verify/webauthn")
    public ResponseEntity<HttpResponse> authenticateVerify(@RequestBody @Valid PasskeyLoginVerifyForm form) {
        PasskeyService.AuthenticationResult result = passkeyService.finishAuthentication(form.getCredential().toString());
        UserDTO userDTO = userService.getUserById(result.userId());
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), PASSKEY_LOGIN));
        UserPrincipal principal = new UserPrincipal(toUser(userDTO), roleService.getRoleByUserId(userDTO.getId()));
        SessionService.TokenPair tokens = sessionService.issueTokenPair(principal, request);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO,
                                "access_token", tokens.accessToken(),
                                "refresh_token", tokens.refreshToken()))
                        .message("Login successful!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
