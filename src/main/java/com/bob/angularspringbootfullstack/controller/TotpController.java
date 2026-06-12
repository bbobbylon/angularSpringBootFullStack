package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.form.TotpCodeForm;
import com.bob.angularspringbootfullstack.form.TotpVerifyForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.*;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * REST endpoints for authenticator-app MFA (SRS §4.5 FR-MFA-4, plan.md M4) — the
 * Account Security Center's backend.
 *
 * <p>Two authorization postures coexist here:
 * <ul>
 *   <li>The {@code /user/totp/*} lifecycle endpoints require an authenticated session.
 *       They are matched by an explicit {@code authenticated()} rule in
 *       {@code SecurityConfig} placed BEFORE the broad {@code POST /**} catch-all,
 *       because that catch-all demands {@code UPDATE:USER} — an authority plain
 *       {@code ROLE_USER} does not hold, and securing one's own account must never
 *       require staff permissions.</li>
 *   <li>{@code POST /user/verify/totp} is public ({@code PUBLIC_URLS}/{@code PUBLIC_ROUTES})
 *       because the caller is mid-login and holds no token yet. Its security comes from
 *       the server-side challenge minted at first-factor success — see
 *       {@link TotpService#verifyLoginChallenge} for why a challenge (not an email)
 *       keys this endpoint.</li>
 * </ul>
 *
 * <p>Responses mirror {@code UserController}'s envelope conventions so the SPA's
 * existing response handling applies unchanged; token issuance on verify matches the
 * SMS {@code /user/verify/code} response shape exactly.
 */
@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
@Slf4j
public class TotpController {

    private final TotpService totpService;
    private final UserService userService;
    private final RoleService roleService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpServletRequest request;

    /**
     * Starts authenticator enrollment for the signed-in user: generates a pending
     * secret and returns it alongside the otpauth URI and a QR code data URI for the
     * Security Center wizard. Idempotent for an unconfirmed enrollment — calling it
     * again simply issues a fresh secret.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with {@code secret}, {@code otpauthUri}, and {@code qrCode}
     */
    @PostMapping("/totp/setup")
    public ResponseEntity<HttpResponse> setupTotp(Authentication authentication) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        TotpService.TotpEnrollment enrollment = totpService.beginEnrollment(userDTO.getId(), userDTO.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("secret", enrollment.secret(),
                                "otpauthUri", enrollment.otpauthUri(),
                                "qrCode", enrollment.qrCode()))
                        .message("Scan the QR code with your authenticator app, then confirm with a code.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Completes enrollment: the submitted code proves the authenticator holds the
     * pending secret, after which TOTP is active and the freshly issued recovery codes
     * are returned for their one-and-only display. Publishes a TOTP_ENROLLED audit
     * event (FR-AUDIT-1's "MFA enrollment change").
     *
     * @param authentication the current Spring Security authentication
     * @param form           the validated body carrying the 6-digit code
     * @return 200 OK with the refreshed user and the plaintext {@code recoveryCodes}
     */
    @PostMapping("/totp/enable")
    public ResponseEntity<HttpResponse> enableTotp(Authentication authentication, @RequestBody @Valid TotpCodeForm form) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        List<String> recoveryCodes = totpService.confirmEnrollment(userDTO.getId(), form.getCode());
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), TOTP_ENROLLED));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserById(userDTO.getId()),
                                "recoveryCodes", recoveryCodes))
                        .message("Authenticator app enabled! Store your recovery codes somewhere safe — they will not be shown again.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Disables authenticator MFA. Requires a live TOTP code or an unused recovery code
     * (enforced by the service) so a hijacked browser session cannot silently strip the
     * second factor. Publishes a TOTP_DISABLED audit event.
     *
     * @param authentication the current Spring Security authentication
     * @param form           the validated body carrying a TOTP or recovery code
     * @return 200 OK with the refreshed user
     */
    @PostMapping("/totp/disable")
    public ResponseEntity<HttpResponse> disableTotp(Authentication authentication, @RequestBody @Valid TotpCodeForm form) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        totpService.disableTotp(userDTO.getId(), form.getCode());
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), TOTP_DISABLED));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserById(userDTO.getId())))
                        .message("Authenticator app disabled.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Reports the signed-in user's TOTP state for the Account Security Center:
     * whether an authenticator is active and how many recovery codes remain unused
     * (so the UI can prompt re-enrollment when the supply runs low).
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with {@code enabled} and {@code recoveryCodesRemaining}
     */
    @GetMapping("/totp/status")
    public ResponseEntity<HttpResponse> totpStatus(Authentication authentication) {
        UserDTO userDTO = userService.getUserById(getAuthenticatedUser(authentication).getId());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("enabled", userDTO.isUsingTotp(),
                                "recoveryCodesRemaining", totpService.countUnusedRecoveryCodes(userDTO.getId())))
                        .message("Authenticator status retrieved.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Public login-completion endpoint: exchanges a live first-factor challenge plus an
     * authenticator (or recovery) code for the user and a fresh token pair — the TOTP
     * counterpart of {@code GET /user/verify/code/{email}/{code}}, but POSTed so neither
     * value reaches URL or proxy logs, and keyed by challenge so account existence is
     * never probeable (NFR-SEC-7).
     *
     * <p>Audit: success publishes LOGIN_ATTEMPT_SUCCESS, plus RECOVERY_CODE_USED when a
     * recovery code was burned. A wrong code publishes no event because the account is
     * deliberately not resolvable from a failed challenge attempt; the failure is
     * captured in server logs only.
     *
     * @param form the validated challenge + code pair
     * @return 200 OK with the user and {@code access_token}/{@code refresh_token}
     */
    @PostMapping("/verify/totp")
    public ResponseEntity<HttpResponse> verifyTotp(@RequestBody @Valid TotpVerifyForm form) {
        TotpService.TotpVerification verification = totpService.verifyLoginChallenge(form.getChallenge(), form.getCode());
        UserDTO userDTO = userService.getUserById(verification.userId());
        if (verification.usedRecoveryCode()) {
            eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), RECOVERY_CODE_USED));
        }
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), LOGIN_ATTEMPT_SUCCESS));
        UserPrincipal principal = new UserPrincipal(toUser(userDTO), roleService.getRoleByUserId(userDTO.getId()));
        // SessionService (plan.md M5) opens a tracked, revocable session — TOTP logins
        // appear in the Security Center device list like every other authentication path.
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
