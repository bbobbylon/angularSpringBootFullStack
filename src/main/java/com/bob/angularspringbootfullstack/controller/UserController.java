package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.LoginRiskAssessment;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.enumeration.StepUpMethod;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.form.*;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.LoginRiskService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger;

import static com.bob.angularspringbootfullstack.constants.Constants.TOKEN_PREFIX;
import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.*;
import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.BRUTE_FORCE_LOCKOUT;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getLoggedInUser;
import static java.time.LocalTime.now;
import static java.util.Map.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;
import static org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated;

/**
 * REST endpoints for user registration, login, 2FA, account/password
 * verification, and token refresh. Wraps every response in HttpResponse for a
 * consistent JSON shape.
 *
 * <p>-----------------------------------------------------------------------
 * TODO(refactor-user-fetch): Standardize how the authenticated user is
 * resolved across all endpoints. Currently three inconsistent patterns exist:
 * -----------------------------------------------------------------------
 *
 * <ol>
 *   <li><b>Re-fetch by email</b> — {@code getProfile} and {@code toggleMFA}
 *       call {@code getAuthenticatedUser(authentication).getEmail()} then do a
 *       secondary DB lookup by email.</li>
 *   <li><b>Re-fetch by ID</b> — {@code updateUserPassword}
 *       and {@code updateAccountSettings} call
 *       {@code getAuthenticatedUser(authentication).getId()} then do a secondary
 *       DB lookup by ID.</li>
 *   <li><b>ID directly from token</b> — {@code refreshToken} skips
 *       {@code getAuthenticatedUser} entirely and reads the subject straight from
 *       the token via {@code tokenProvider.getSubject(...)}.</li>
 * </ol>
 *
 * <p><b>Recommended approach:</b> standardize on ID-based lookups for all
 * secondary DB fetches (primary key = fastest lookup) and only perform a
 * secondary DB fetch when fresh data is actually needed after a mutation.
 * For passing context to a service call, {@code getAuthenticatedUser(authentication)}
 * already returns a {@link com.bob.angularspringbootfullstack.dto.UserDTO} from
 * the JWT — no extra DB round-trip is needed.
 * -----------------------------------------------------------------------
 */
@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {
    // there is a space after Bearer to split the header into two parts and extract the token more easily; this is a standard convention for Authorization headers and is required for the substring operation in refreshToken to work correctly

    private static final int DEFAULT_PAGE_SIZE = 10;
    /** Maximum consecutive login failures allowed within the brute-force window (M6). */
    private static final int BRUTE_FORCE_MAX = 5;
    /** Sliding window length in minutes for the brute-force failure count (M6). */
    private static final int BRUTE_FORCE_WINDOW_MINUTES = 15;
    private final UserService userService;
    private final RoleService roleService;
    private final AuthenticationManager authenticationManager;
    private final HttpServletRequest request;
    private final ApplicationEventPublisher eventPublisher;
    private final EventService eventService;
    private final TotpService totpService;
    private final SessionService sessionService;
    /** Login-anomaly detection and its step-up escalation (FR-TPF-1); consulted on every sign-in. */
    private final LoginRiskService loginRiskService;

    /**
     * Filesystem directory profile images are served from; injected from
     * {@code app.image.storage-path} (env {@code IMAGE_STORAGE_PATH}). Field injection
     * mirrors the {@code @Value} pattern already used here and keeps the value out of
     * the Lombok {@code @RequiredArgsConstructor}, which is reserved for bean deps.
     */
    @Value("${app.image.storage-path}")
    private String imageStoragePath;

    /**
     * Registers a new user. Validates the payload, creates the user via
     * UserService, and returns the created DTO with a 201 Location header
     * pointing to the new resource.
     *
     * @param user the registration payload (validated with @Valid)
     * @return 201 CREATED with the new user inside an HttpResponse
     */
    @PostMapping("/register")
    public ResponseEntity<HttpResponse> saveUser(@RequestBody @Valid User user) {
        UserDTO userDTO = userService.createUser(user);
        return ResponseEntity.created(getUri()).body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO))
                        .message(String.format("User created successfully for user: " + userDTO.getEmail()))
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    /**
     * Builds the Location URI returned with a 201 CREATED registration
     * response.
     *
     * @return the URI pointing to the new user resource
     */
    private URI getUri() {
        return URI.create(ServletUriComponentsBuilder.fromCurrentContextPath().path("/user/get/<userId>").toUriString());
    }

    /**
     * Verifies a 2FA code submitted via URL and, on success, returns the user
     * along with a freshly issued access/refresh token pair. Used to complete
     * login for accounts with 2FA enabled.
     *
     * <p>Token issuance goes through {@link SessionService#issueTokenPair} (plan.md M5)
     * so the new session appears in the Security Center's device list and its refresh
     * token participates in rotation/revocation like every other session.
     *
     * @param email the email of the user verifying the code
     * @param code  the 2FA code received over SMS
     * @return 200 OK with user and tokens
     */
    @GetMapping("/verify/code/{email}/{code}")
    public ResponseEntity<HttpResponse> verifyCode(@PathVariable String email, @PathVariable String code) {
        try {
            UserDTO userDTO = userService.verifyCode(email, code);
            eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), LOGIN_ATTEMPT_SUCCESS));
            SessionService.TokenPair tokens = sessionService.issueTokenPair(getUserPrincipal(userDTO), request);
            return ResponseEntity.ok(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .data(of("user", userDTO, "access_token", tokens.accessToken(), "refresh_token", tokens.refreshToken()))
                            .message("Login successful!")
                            .status(OK)
                            .statusCode(OK.value())
                            .build());
        } catch (Exception e) {
            eventPublisher.publishEvent(new NewUserEvent(email, LOGIN_ATTEMPT_FAILURE));
            throw e;
        }
    }

    /**
     * Redelivers an outstanding 2FA/step-up code — the "resend code" link on the verify screen.
     *
     * <p>Pre-authentication by definition (same as {@link #verifyCode}, which this exists to
     * support), so it is listed in {@code Constants.PUBLIC_URLS}/{@code PUBLIC_ROUTES} and sits in
     * {@code RateLimitFilter}'s tighter auth tier (10 req/min per IP) rather than the global tier —
     * an unauthenticated "send me a code" endpoint is a live SMS/email-bombing target, not just a
     * general-purpose route.
     *
     * <p><b>Anti-enumeration (FR-AUTH-4):</b> {@link UserService#resendVerificationCode} silently
     * no-ops for an unknown email, a TOTP account, or an account with nothing pending, so this
     * always returns the identical 200 regardless — the response can never be used to test which
     * emails are registered or how an account has MFA configured.
     *
     * @param form validated body carrying the email a code may be outstanding for
     * @return 200 OK with a deliberately non-committal message
     */
    @PostMapping("/verify/resend")
    public ResponseEntity<HttpResponse> resendVerificationCode(@RequestBody @Valid ResendCodeForm form) {
        userService.resendVerificationCode(form.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("If a verification code is pending for that account, we've sent it again.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Wraps the authenticated user and their role in a UserPrincipal for token minting.
     * Uses the already-fetched UserDTO, avoiding redundant database lookups.
     *
     * @param userDTO an authenticated user (already fetched from DB)
     * @return a UserPrincipal carrying the User and Role
     */
    private UserPrincipal getUserPrincipal(UserDTO userDTO) {
        return new UserPrincipal(toUser(userDTO), roleService.getRoleByUserId(userDTO.getId()));
    }

    /**
     * Activates a newly registered account using the UUID key embedded in the
     * verification email link.
     *
     * @param yeet the activation key from the URL
     * @return 200 OK with a message indicating whether the account was newly
     * verified or already verified
     */
    @GetMapping("/verify/account/{key}")
    public ResponseEntity<HttpResponse> verifyAccount(@PathVariable("key") String yeet) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message(userService.verifyAccount(yeet).isEnabled() ? "Your account is already verified. Please log in." : "Account verified successfully! You can now log in.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates the authenticated user's password. Reloads the user from the DB
     * to ensure the operation is scoped to the authenticated principal. On
     * success, issues a fresh token pair so the user's session remains valid
     * despite the {@code passwordChangedAt} invalidation check in
     * {@link com.bob.angularspringbootfullstack.tokenprovider.TokenProvider#isTokenValid}.
     *
     * @param authentication     the current Spring Security authentication
     * @param updatePasswordForm the current password plus the new password and confirmation
     * @return 200 OK with the user and fresh access/refresh tokens
     */
    @PatchMapping("/update/password")
    public ResponseEntity<HttpResponse> updateUserPassword(Authentication authentication, @RequestBody @Valid UpdatePasswordForm updatePasswordForm) {
        UserDTO authUser = getAuthenticatedUser(authentication);
        UserDTO dbUser = userService.getUserById(authUser.getId());
        if (!authUser.getId().equals(dbUser.getId()))
            throw new ApiException("Unauthorized request.");
        userService.updatePassword(dbUser.getId(), updatePasswordForm.getCurrentPassword(), updatePasswordForm.getNewPassword(), updatePasswordForm.getConfirmPassword());
        eventPublisher.publishEvent(new NewUserEvent(authUser.getEmail(), PASSWORD_UPDATE));
        // The passwordChangedAt check already kills every outstanding JWT (FR-JWT-6);
        // revoking the session rows keeps the Security Center's device list truthful,
        // then a fresh session is opened so THIS browser stays signed in (plan.md M5).
        sessionService.revokeAllSessions(dbUser.getId());
        SessionService.TokenPair tokens = sessionService.issueTokenPair(getUserPrincipal(dbUser), request);
        long pwTotalElements = eventService.countEventsByUserId(dbUser.getId());
        int pwTotalPages = (int) Math.ceil((double) pwTotalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(ofEntries(
                                entry("user", dbUser),
                                entry("roles", roleService.getAllRoles()),
                                entry("events", eventService.getEventsByUserId(dbUser.getId(), 0, DEFAULT_PAGE_SIZE)),
                                entry("eventsTotalElements", pwTotalElements),
                                entry("eventsTotalPages", pwTotalPages),
                                entry("access_token", tokens.accessToken()),
                                entry("refresh_token", tokens.refreshToken())))
                        .message("Your password has been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    // NOTE(FR-RBAC-4): the former PATCH /user/update/role/{roleName} endpoint was removed.
    // It let any authenticated user reassign their OWN role (no authority gate existed on
    // PATCH routes), which is a privilege-escalation hole. Role reassignment is now an
    // administrative operation only — see AdminUserController#updateUserRole, which requires
    // the UPDATE:ROLE authority and forbids self-targeting.

    /**
     * Updates the authenticated user's account settings (enabled / non-locked flags).
     * Reads both flags from the validated {@link SettingsForm} body and persists them
     * via the service. Returns the refreshed user alongside the full roles list.
     *
     * @param authentication the current Spring Security authentication
     * @param settingsForm   the validated payload carrying {@code enabled} and {@code notLocked}
     * @return 200 OK with the updated user and the full roles list
     */
    @PatchMapping("/update/settings")
    public ResponseEntity<HttpResponse> updateAccountSettings(Authentication authentication, @RequestBody @Valid SettingsForm settingsForm) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        userService.updateAccountSettings(userDTO.getId(), settingsForm.getEnabled(), settingsForm.getNotLocked());
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), ACCOUNT_SETTINGS_UPDATE));
        long settingsTotalElements = eventService.countEventsByUserId(userDTO.getId());
        int settingsTotalPages = (int) Math.ceil((double) settingsTotalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserById(userDTO.getId()),
                                "events", eventService.getEventsByUserId(userDTO.getId(), 0, DEFAULT_PAGE_SIZE),
                                "eventsTotalElements", settingsTotalElements,
                                "eventsTotalPages", settingsTotalPages,
                                "roles", roleService.getAllRoles()))
                        .message("Your account settings have been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Flips the authenticated user's MFA (two-factor authentication) flag.
     * Requires a phone number to be set on the account; the service throws if one
     * is missing.
     *
     * @param authentication the current Spring Security authentication
     * @return 200 OK with the updated user and the full roles list
     */
    @PatchMapping("/update/togglemfa")
    public ResponseEntity<HttpResponse> toggleMFA(Authentication authentication) {
        UserDTO userDTO = userService.toggleMFA(getAuthenticatedUser(authentication).getEmail());
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), MFA_UPDATE));
        long mfaTotalElements = eventService.countEventsByUserId(userDTO.getId());
        int mfaTotalPages = (int) Math.ceil((double) mfaTotalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO,
                                "events", eventService.getEventsByUserId(userDTO.getId(), 0, DEFAULT_PAGE_SIZE),
                                "eventsTotalElements", mfaTotalElements,
                                "eventsTotalPages", mfaTotalPages,
                                "roles", roleService.getAllRoles()))
                        .message("Multi-Factor authentication setting has been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }


    /**
     * Uploads and persists a new profile image for the authenticated user.
     * <p>
     * Saves the uploaded file to the configurable storage directory
     * ({@code app.image.storage-path}, env {@code IMAGE_STORAGE_PATH}; see
     * {@code WebMvcConfig}), constructs a URL pointing to
     * {@code GET /user/profile/image/{email}.png}, and updates the user's
     * {@code image_url} column in the database via {@code UserService.updateProfileImage}.
     * The configurable path makes image storage portable across local dev, Docker,
     * and cloud — in containers it points at a mounted volume so uploads survive restarts.
     *
     * @param authentication the current Spring Security authentication
     * @param image          the uploaded PNG file sent as {@code multipart/form-data}
     *                       under the key {@code "image"}
     * @return 200 OK with the updated user and the full roles list
     */
    @PatchMapping("/update/image")
    public ResponseEntity<HttpResponse> updateProfileImage(Authentication authentication, @RequestParam("image") MultipartFile image) {
        UserDTO userDTO = getAuthenticatedUser(authentication);
        userService.updateProfileImage(userDTO, image);
        eventPublisher.publishEvent(new NewUserEvent(userDTO.getEmail(), PROFILE_PICTURE_UPDATE));
        long imgTotalElements = eventService.countEventsByUserId(userDTO.getId());
        int imgTotalPages = (int) Math.ceil((double) imgTotalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserById(userDTO.getId()),
                                "events", eventService.getEventsByUserId(userDTO.getId(), 0, DEFAULT_PAGE_SIZE),
                                "eventsTotalElements", imgTotalElements,
                                "eventsTotalPages", imgTotalPages,
                                "roles", roleService.getAllRoles()))
                        .message("Profile image has been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Serves a profile image file from the configured image directory.
     * <p>
     * Reads {@code {app.image.storage-path}/{fileName}} and returns the raw bytes with
     * {@code Content-Type: image/png}. The URL pattern {@code /user/image/**} is in
     * {@code Constants.PUBLIC_URLS}, so the browser's {@code <img>} tag can load it without
     * a token.
     * <p>
     * Hardened: the resolved path is confined to the storage directory (path-traversal guard
     * — a crafted {@code fileName} can no longer escape the folder), and a missing file
     * returns {@code 404} instead of propagating a raw {@code IOException} as a 500.
     *
     * @param fileName the image filename (e.g. {@code user@example.com.png}) from the URL
     * @return 200 with PNG bytes, or 404 when the image does not exist
     * @throws IOException if an existing, in-bounds file cannot be read
     */
    @GetMapping(value = "/image/{fileName}", produces = IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getProfileImage(@PathVariable String fileName) throws IOException {
        Path base = Paths.get(imageStoragePath).toAbsolutePath().normalize();
        Path target = base.resolve(fileName).normalize();
        // Confine the resolved path to the storage directory — defeats "../" traversal.
        if (!target.startsWith(base) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readAllBytes(target));
    }

    /**
     * Exchanges a valid refresh token for a ROTATED token pair (plan.md M5,
     * FR-JWT-5): the presented token's session row is retired and a new refresh
     * token in the same family is returned alongside a fresh access token — the
     * old refresh token is dead from this moment, and replaying it triggers
     * reuse detection (the whole family is revoked).
     *
     * <p>All validation lives in {@link SessionService#rotate}: JWT signature and
     * expiry, the {@code passwordChangedAt} check, and the session-store verdicts
     * (unknown, superseded, or revoked tokens are refused). Failures surface as
     * {@code ApiException} through the global handler; only a structurally absent
     * header short-circuits to 400 here.
     *
     * @param request the HTTP request, expected to carry "Authorization: Bearer &lt;refresh&gt;"
     * @return 200 OK with the user and the rotated token pair, or 400 when the header is missing
     */
    @GetMapping("/refresh/token")
    public ResponseEntity<HttpResponse> sendNewRefreshToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(TOKEN_PREFIX)) {
            return ResponseEntity.badRequest().body(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .reason("Invalid or missing token. Please try again.")
                            .message("The refresh token is invalid or missing. Please log in again to obtain a new token.")
                            .status(BAD_REQUEST)
                            .statusCode(BAD_REQUEST.value())
                            .build());
        }
        SessionService.TokenPair tokens = sessionService.rotate(header.substring(TOKEN_PREFIX.length()), request);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", tokens.user(), "access_token", tokens.accessToken(), "refresh_token", tokens.refreshToken()))
                        .message("New refresh token sent successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns the current user's profile along with all available roles.
     *
     * <p>The {@link Authentication} was installed by {@code CustomAuthFilter}, which stores a
     * {@link UserDTO} directly as the principal (see {@code TokenProvider#getAuthentication}).
     * {@link com.bob.angularspringbootfullstack.utils.UserUtils#getAuthenticatedUser(Authentication)}
     * casts it back so we can read the email and reload the full profile — this avoids
     * {@code Authentication#getName()}, which would fall back to {@code UserDTO#toString()}.
     * The full roles list is included, so the frontend can populate the role selector in
     * the Authorization tab without a separate request.
     *
     * @param authentication the current Authentication injected by Spring Security
     * @return 200 OK with the user as a DTO and the full collection of roles
     */
    @GetMapping("/profile")
    public ResponseEntity<HttpResponse> getProfile(Authentication authentication) {
        UserDTO userDTO = userService.getUserByEmail(getAuthenticatedUser(authentication).getEmail());
        long totalElements = eventService.countEventsByUserId(userDTO.getId());
        int totalPages = (int) Math.ceil((double) totalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO,
                                "events", eventService.getEventsByUserId(userDTO.getId(), 0, DEFAULT_PAGE_SIZE),
                                "eventsTotalElements", totalElements,
                                "eventsTotalPages", totalPages,
                                "roles", roleService.getAllRoles()))
                        .message("We have fetched your profile for you!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns one page of audit events for the authenticated user.
     *
     * <p>Called by the frontend pagination controls on the Profile page after the initial
     * load — keeps page navigation from re-fetching the user object and roles list.
     *
     * @param authentication the current Spring Security authentication
     * @param page           zero-based page index (default 0)
     * @param size           page size (default {@value DEFAULT_PAGE_SIZE})
     * @return 200 OK with the events page, total element count, and total page count
     */
    @GetMapping("/events")
    public ResponseEntity<HttpResponse> getUserEvents(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UserDTO userDTO = userService.getUserByEmail(getAuthenticatedUser(authentication).getEmail());
        long totalElements = eventService.countEventsByUserId(userDTO.getId());
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("events", eventService.getEventsByUserId(userDTO.getId(), page, size),
                                "eventsTotalElements", totalElements,
                                "eventsTotalPages", totalPages))
                        .message("Retrieved user events!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates the authenticated user's profile with the supplied form data.
     * <p>
     * The target user ID is ALWAYS sourced from the authenticated principal — any {@code id}
     * present in the request body is overwritten and ignored. This closes a broken
     * object-level-authorization (IDOR) gap: because the {@code PATCH /**} rule in
     * {@code SecurityConfig} only requires the {@code UPDATE:USER} authority that every
     * {@code ROLE_USER} already holds, trusting a client-supplied id would let any
     * authenticated user edit another user's profile. {@code getAuthenticatedUser} reads the
     * {@link UserDTO} that {@code CustomAuthFilter} installed as the principal, so resolving
     * the id requires no extra database round-trip.
     *
     * @param authentication the current authentication injected by Spring Security
     * @param user           the validated update payload; its {@code id} is ignored and replaced
     * @return 200 OK with the updated user as a DTO
     */
    @PatchMapping("/update")
    public ResponseEntity<HttpResponse> updateUser(Authentication authentication, @RequestBody @Valid UpdateForm user) {
        // Bind the update to the caller's OWN id, never the body's — the JWT principal is the
        // single source of truth for whose row is modified (IDOR fix).
        user.setId(getAuthenticatedUser(authentication).getId());
        UserDTO updatedUser = userService.updateUserDTO(user);
        eventPublisher.publishEvent(new NewUserEvent(updatedUser.getEmail(), PROFILE_UPDATE));
        long updateTotalElements = eventService.countEventsByUserId(updatedUser.getId());
        int updateTotalPages = (int) Math.ceil((double) updateTotalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", updatedUser,
                                "events", eventService.getEventsByUserId(updatedUser.getId(), 0, DEFAULT_PAGE_SIZE),
                                "eventsTotalElements", updateTotalElements,
                                "eventsTotalPages", updateTotalPages,
                                "roles", roleService.getAllRoles()))
                        .message("Your profile has been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }


    // NOTE(admin-update): DONE. The admin "edit another user's profile" operation now lives at
    // PATCH /admin/user/{id}/update in AdminUserController#updateUserByAdmin — it trusts the path id
    // (not the JWT), is gated by UPDATE:USER at both the URL and method layers, is organization-scoped,
    // refuses self-targeting, and audits the change against the target user. The frontend
    // Home > Users > User's Name screen should wire its save action to that endpoint.

    /**
     * Starts the password reset flow for the given email by generating a
     * one-time reset URL via UserService.
     *
     * @param email the email requesting a reset
     * @return 200 OK with a message advising the user to check their inbox
     */
    @GetMapping("/resetpassword/{email}")
    public ResponseEntity<HttpResponse> resetPassword(@PathVariable String email) {
        userService.resetPassword(email);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Email sent to reset password. Please check your inbox. If you don't see it, please check your spam folder.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Resolves a password reset link to its user so the frontend can render
     * the new-password form. Returns 200 OK if the link is still valid.
     *
     * @param key the UUID portion of the reset URL
     * @return 200 OK with the user awaiting a new password
     */
    @GetMapping("/verify/password/{key}")
    public ResponseEntity<HttpResponse> verifyPasswordURL(@PathVariable String key) {
        UserDTO userDTO = userService.verifyPasswordKey(key);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO))
                        .message("Please enter your new password")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Completes the password reset flow by setting a new password for the user
     * identified by the {@code userID} in the request body. The caller — the
     * reset-password page on the frontend — obtained that userID from the prior
     * {@code GET /user/verify/password/{key}} response, so the URL key (and the
     * password itself) never has to appear in a query string.
     *
     * @param form validated body containing {@code userID}, {@code newPassword},
     *             and {@code confirmPassword}
     * @return 200 OK on success
     */
    @PutMapping("/new/password")
    public ResponseEntity<HttpResponse> setNewPassword(@RequestBody @Valid NewPasswordForm form) {
        userService.setNewPassword(form.getUserID(), form.getNewPassword(), form.getConfirmPassword());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Password reset successful! You can now log in with your new password.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Catch-all for requests that don't match any /user mapping. Returns a
     * 400 with an HttpResponse describing the missing route.
     *
     * @param request the unmatched HTTP request
     * @return 400 BAD_REQUEST with a generic explanation
     */
    @RequestMapping("/error")
    public ResponseEntity<HttpResponse> errorHandling(HttpServletRequest request) {
        log.info(String.valueOf(request));
        return ResponseEntity.badRequest().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("An unknown error has occurred. There is no mapping for a " + request.getMethod() + "request for this path on our server. Sorry! Please try something else.")
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value())
                        .build());
    }

    /**
     * Authenticates a user by email and password.
     *
     * <p>{@code AuthenticationManager} returns an {@link Authentication} whose principal is a
     * {@link UserPrincipal}, so we unwrap it with
     * {@link com.bob.angularspringbootfullstack.utils.UserUtils#getLoggedInUser(Authentication)}.
     * When 2FA is enabled, the response only signals that a verification code was sent; otherwise
     * it returns the user along with a fresh access and refresh token pair.
     *
     * <p><b>Risk-adaptive step-up (FR-TPF-1).</b> Once the first factor succeeds, the sign-in is
     * compared against this account's own history via {@link LoginRiskService#assess}. The verdict
     * never changes <em>whether</em> an enrolled second factor is challenged — a TOTP or SMS user is
     * challenged either way — it only decides what happens to an account with <em>no</em> second
     * factor: an ordinary-looking login proceeds straight to tokens, while a flagged one is
     * escalated to {@link StepUpMethod#EMAIL_CODE} so a stolen password alone is not enough.
     *
     * <p>The assessment is therefore run before the branches rather than inside the last one, so
     * every outcome records <em>which</em> step-up covered the risk (see
     * {@link LoginRiskService#recordSuspiciousLogin}, a no-op when nothing was flagged). Note the
     * client cannot tell an anomaly-driven email challenge from an ordinary 2FA prompt: both return
     * the same shape, with no risk signal echoed back (see {@link LoginRiskAssessment}).
     *
     * @param loginForm validated email and password
     * @return 200 OK with either a "code sent" message or login data
     */
    @PostMapping("/login")
    public ResponseEntity<HttpResponse> login(@RequestBody @Valid LoginForm loginForm) {
        UserDTO userDTO = authenticate(loginForm.getEmail(), loginForm.getPassword());
        //UserDTO userDTO = getLoggedInUser(authentication);
        // Side-effect free, and safe to run on every login: it neither writes nor sends anything,
        // so the branches below stay free to decide what the verdict actually means.
        LoginRiskAssessment assessment = loginRiskService.assess(userDTO, request);
        // MFA precedence (FR-MFA-4): a confirmed authenticator app supersedes the SMS code
        // path — TOTP is the stronger factor, and sending an SMS a TOTP user will never
        // type would only burn Twilio quota and confuse the login screen.
        if (userDTO.isUsingTotp()) {
            loginRiskService.recordSuspiciousLogin(userDTO, assessment, StepUpMethod.TOTP);
            return sendTotpChallenge(userDTO);
        }
        if (userDTO.isUsing2FA()) {
            loginRiskService.recordSuspiciousLogin(userDTO, assessment, StepUpMethod.SMS_CODE);
            return sendVerificationCode(userDTO);
        }
        if (assessment.elevated()) {
            // No enrolled second factor and the sign-in looks unfamiliar: this is the one branch
            // FR-TPF-1 actually adds. Without it, a leaked password would open a session outright.
            loginRiskService.recordSuspiciousLogin(userDTO, assessment, StepUpMethod.EMAIL_CODE);
            return sendStepUpCode(userDTO, assessment);
        }
        return sendResponse(userDTO);
    }

    /**
     * Validates the email and delegates credential verification to the {@link org.springframework.security.authentication.AuthenticationManager}.
     *
     * <p><b>Anti-enumeration (FR-AUTH-4, NFR-SEC-7).</b> An unknown email and a wrong password
     * MUST be indistinguishable to the caller. Two things make that true here: the account is
     * resolved through {@link #findUserOrNull(String)} (which swallows the repository's
     * {@code UsernameNotFoundException} to {@code null} instead of letting it escape as a 500),
     * and every credential failure is rethrown as one generic {@code "Invalid email or password."}
     * {@link ApiException} — the underlying exception message (which embeds the email) is never
     * echoed to the client. Both cases therefore yield an identical 400 with an identical body.
     * Disabled / locked accounts (FR-AUTH-5) keep their own actionable messages, since those are
     * legitimate state signals rather than credential checks.
     *
     * <p>Audit events mirror the same rule (FR-AUDIT-3): {@link EventType#LOGIN_ATTEMPT} and
     * {@link EventType#LOGIN_ATTEMPT_FAILURE} are recorded only for a KNOWN account, so the audit
     * log itself never becomes an enumeration oracle. On success, {@link EventType#LOGIN_ATTEMPT_SUCCESS}
     * is published unless 2FA is enabled (success is recorded after code verification instead).
     *
     * @param email    the submitted email address
     * @param password the submitted password
     * @return the authenticated {@link UserDTO} on success
     * @throws com.bob.angularspringbootfullstack.exception.ApiException on any authentication failure
     */
    private UserDTO authenticate(String email, String password) {
        // Resolve WITHOUT throwing on a miss: the repo raises UsernameNotFoundException for an
        // unknown email, which (uncaught, outside the try) used to surface as a 500 whose
        // devMessage leaked the email — an enumeration oracle. Null here keeps unknown emails on
        // the exact same path as a wrong password.
        UserDTO userByEmail = findUserOrNull(email);
        try {
            // M6: reject the attempt early when the sliding-window failure count exceeds the
            // threshold. Gated on a known account, so an unknown email falls through to the same
            // generic credential failure below rather than revealing the account exists.
            if (null != userByEmail &&
                    eventService.countRecentFailuresByEmail(email, BRUTE_FORCE_WINDOW_MINUTES) >= BRUTE_FORCE_MAX) {
                // Persist a hard lock so the account no longer auto-recovers when the window rolls off:
                // once tripped, only an administrator can unlock it. The CLIENT message is unchanged
                // (still the generic window-wait text) so this introduces no new enumeration signal —
                // the lock is server-side state, surfaced only in the console.
                lockAccountForBruteForce(userByEmail);
                throw new ApiException("Too many failed login attempts. Please wait " +
                        BRUTE_FORCE_WINDOW_MINUTES + " minutes before trying again.");
            }
            if (null != userByEmail) {
                eventPublisher.publishEvent(new NewUserEvent(email, EventType.LOGIN_ATTEMPT));
            }
            Authentication authentication = authenticationManager.authenticate(unauthenticated(email, password));
            UserDTO loggedInUser = getLoggedInUser(authentication);
            // Console-only RBAC visibility: record the resolved role + authorities that will be
            // baked into the JWT. Never surfaced to the client (see AuthDiagnosticsLogger).
            AuthDiagnosticsLogger.logGranted(email, loggedInUser, request);
            if (!loggedInUser.isUsing2FA()) {
                eventPublisher.publishEvent(new NewUserEvent(email, EventType.LOGIN_ATTEMPT_SUCCESS));
            }
            return loggedInUser;
        } catch (ApiException e) {
            // Brute-force rejection: its message is intentionally non-enumerating; surface as-is.
            recordLoginFailure(userByEmail, email);
            AuthDiagnosticsLogger.logDenied(email, userByEmail, BRUTE_FORCE_LOCKOUT, e, request);
            throw e;
        } catch (DisabledException | LockedException e) {
            // Legitimate account-state signals (FR-AUTH-5) — keep the actionable message.
            recordLoginFailure(userByEmail, email);
            AuthDiagnosticsLogger.logDenied(email, userByEmail, AuthDiagnosticsLogger.classify(userByEmail, e), e, request);
            throw new ApiException(e.getMessage());
        } catch (Exception e) {
            // Bad password, unknown email, or anything else: ONE generic message so the cases are
            // indistinguishable (FR-AUTH-4, NFR-SEC-7). Never echo the underlying exception text.
            // The TRUE reason is classified and logged server-side only — the client still gets the
            // one generic message above; the console gets "unknown email" vs "bad password" vs
            // "no role assigned" so operators can act on it.
            recordLoginFailure(userByEmail, email);
            AuthDiagnosticsLogger.logDenied(email, userByEmail, AuthDiagnosticsLogger.classify(userByEmail, e), e, request);
            throw new ApiException("Invalid email or password.");
        }
    }

    /**
     * Looks up a user by email for the login flow WITHOUT throwing when the email is unknown.
     * <p>
     * {@code UserService.getUserByEmail} (via {@code UserRepoImpl}) raises
     * {@link UsernameNotFoundException} for a miss; swallowing it to {@code null} here is what lets
     * an unknown email and a wrong password follow the identical failure path, so neither the
     * status code, the response body, nor the audit trail reveals whether the account exists
     * (FR-AUTH-4, NFR-SEC-7, FR-AUDIT-3).
     *
     * @param email the submitted email
     * @return the matching {@link UserDTO}, or {@code null} when no account has that email
     */
    private UserDTO findUserOrNull(String email) {
        try {
            return userService.getUserByEmail(email);
        } catch (UsernameNotFoundException e) {
            return null;
        }
    }

    /**
     * Publishes a {@link EventType#LOGIN_ATTEMPT_FAILURE} event only for a KNOWN account, so the
     * audit log does not itself leak which emails are registered (FR-AUDIT-3).
     *
     * @param userByEmail the resolved account, or {@code null} for an unknown email
     * @param email       the submitted email (event subject when the account is known)
     */
    private void recordLoginFailure(UserDTO userByEmail, String email) {
        if (null != userByEmail) {
            eventPublisher.publishEvent(new NewUserEvent(email, EventType.LOGIN_ATTEMPT_FAILURE));
        }
    }

    /**
     * Applies a persistent account lock once the brute-force threshold is reached (M6 hardening).
     * <p>
     * Sets {@code notLocked = false} while preserving the account's current {@code enabled} flag, so
     * a locked account cannot recover simply by waiting out the {@value #BRUTE_FORCE_WINDOW_MINUTES}-minute
     * window — an administrator must explicitly unlock it (via {@code AdminUserController#updateAccountSettings}).
     * On the next attempt after the window clears, Spring's {@code DaoAuthenticationProvider} raises a
     * {@link LockedException} during its pre-authentication checks, which surfaces the actionable
     * "account is locked" signal (FR-AUTH-5).
     * <p>
     * The write is skipped when the account is already locked, to avoid redundant DB updates and
     * repeated log lines on every subsequent hammering attempt.
     *
     * @param userByEmail the known account that just crossed the failure threshold (never {@code null} here)
     */
    private void lockAccountForBruteForce(UserDTO userByEmail) {
        if (!userByEmail.isNotLocked()) {
            return; // already locked — nothing to persist, and no need to re-log
        }
        userService.updateAccountSettings(userByEmail.getId(), userByEmail.isEnabled(), false);
        AuthDiagnosticsLogger.logAutoLock(userByEmail, BRUTE_FORCE_MAX, BRUTE_FORCE_WINDOW_MINUTES, request);
    }

    /**
     * Mints a server-side MFA challenge for a TOTP-enabled user whose first factor just
     * succeeded, and returns it to the SPA instead of tokens. The challenge — not the
     * email — is what {@code POST /user/verify/totp} later accepts, because a TOTP code
     * always exists on the user's device: without this server-side proof that the
     * password step happened, a public verify endpoint would let anyone holding the
     * authenticator skip the first factor entirely (contrast with the SMS flow, where
     * the code's existence itself proves authentication succeeded).
     *
     * @param userDTO the user awaiting authenticator verification
     * @return 200 OK with the user and the opaque {@code challenge}; no tokens (FR-MFA-3)
     */
    private ResponseEntity<HttpResponse> sendTotpChallenge(UserDTO userDTO) {
        String challenge = totpService.createLoginChallenge(userDTO.getId());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO, "challenge", challenge))
                        .message("Enter the code from your authenticator app.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Asks UserService to send a 2FA code and returns a 200 OK response
     * informing the client a code is on the way. Used when the authenticated
     * user has 2FA enabled.
     *
     * @param userDTO the user awaiting 2FA verification
     * @return 200 OK with a "code sent" message
     */
    private ResponseEntity<HttpResponse> sendVerificationCode(UserDTO userDTO) {
        userService.sendVerificationCode(userDTO);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO))
                        .message("2FA verification code was sent!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Emails a one-time code to an account whose sign-in was flagged as anomalous but which has no
     * enrolled second factor, and withholds tokens until that code is presented (FR-TPF-1).
     *
     * <p>The code is minted and stored by the <em>same</em> {@code twofactorverifications} row the
     * SMS flow uses, so it is completed through the existing
     * {@code GET /user/verify/code/{email}/{code}} endpoint — no second verification endpoint, no
     * second expiry rule, and the single-outstanding-code guarantee (delete-then-insert on a UNIQUE
     * {@code user_id}) applies unchanged.
     *
     * <p><b>What the client is told.</b> The body carries {@code stepUp: true} so the SPA knows to
     * render the code panel for a user whose {@code using2FA} flag is false — a purely mechanical
     * hint, not a risk disclosure. It deliberately does <em>not</em> carry the reason: that travels
     * to the account owner's inbox and the audit log instead, channels an attacker holding only a
     * stolen password cannot read. The user-visible message stays generic for the same reason.
     *
     * @param userDTO    the account that passed its first factor but must now prove possession
     * @param assessment the verdict, whose {@link LoginRiskAssessment#describe()} summary explains
     *                   the challenge in the email body only
     * @return 200 OK with the user and the {@code stepUp} marker; no tokens
     */
    private ResponseEntity<HttpResponse> sendStepUpCode(UserDTO userDTO, LoginRiskAssessment assessment) {
        userService.sendStepUpCode(userDTO, assessment.describe());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO, "stepUp", true))
                        .message("For your security, we emailed you a verification code. Enter it to finish signing in.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Builds the standard login success response: the user plus a 30-minute
     * access token and a 5-day refresh token, issued through
     * {@link SessionService#issueTokenPair} so the login opens a tracked,
     * revocable session (plan.md M5). (Doc fix: this previously claimed 230
     * minutes; {@code ACCESS_TOKEN_EXPIRE_TIME} is and was 1,800,000 ms = 30 min.)
     *
     * @param userDTO the successfully authenticated user
     * @return 200 OK with user data and both tokens
     */
    private ResponseEntity<HttpResponse> sendResponse(UserDTO userDTO) {
        SessionService.TokenPair tokens = sessionService.issueTokenPair(getUserPrincipal(userDTO), request);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO, "access_token", tokens.accessToken(), "refresh_token", tokens.refreshToken()))
                        .message("Login successful!")
                        .devMessage("AuthenticationManager succeeded; 30-min access token and 5-day refresh token issued via SessionService (tracked session).")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

}
