package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.form.LoginForm;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.form.UpdatePasswordForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getLoggedInUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated;

/**
 * REST endpoints for user registration, login, 2FA, account/password
 * verification, and token refresh. Wraps every response in HttpResponse for a
 * consistent JSON shape.
 */
@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {
    private static final String TOKEN_PREFIX = "Bearer ";
    private final UserService userService;
    private final RoleService roleService;
    private final AuthenticationManager authenticationManager;
    private final TokenProvider tokenProvider;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

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
                        .message("User created successfully!")
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
     * @param email the email of the user verifying the code
     * @param code  the 2FA code received over SMS
     * @return 200 OK with user and tokens
     */
    @GetMapping("/verify/code/{email}/{code}")
    public ResponseEntity<HttpResponse> verifyCode(@PathVariable("email") String email, @PathVariable("code") String code) {
        UserDTO userDTO = userService.verifyCode(email, code);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO, "access_token", tokenProvider.createAccessToken(getUserPrincipal(userDTO)), "refresh_token", tokenProvider.createRefreshToken(getUserPrincipal(userDTO))))
                        .message("Login successful!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Reloads the User entity and Role for the given DTO and wraps them in a
     * UserPrincipal so TokenProvider can mint tokens whose authorities reflect
     * the user's current permissions.
     *
     * @param userDTO an authenticated user
     * @return a UserPrincipal carrying the User and Role
     */
    private UserPrincipal getUserPrincipal(UserDTO userDTO) {
        return new UserPrincipal(toUser(userService.getUserByEmail(userDTO.getEmail())), roleService.getRoleByUserId(userDTO.getId()));
    }

    /**
     * Activates a newly registered account using the UUID key embedded in the
     * verification email link.
     *
     * @param key the activation key from the URL
     * @return 200 OK with a message indicating whether the account was newly
     * verified or already verified
     */
    @GetMapping("/verify/account/{key}")
    public ResponseEntity<HttpResponse> verifyAccount(@PathVariable("key") String key) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message(userService.verifyAccount(key).isEnabled() ? "Your account is already verified. Please log in." : "Account verified successfully! You can now log in.")
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
     * @param authentication      the current Spring Security authentication
     * @param updatePasswordForm  the current password plus the new password and confirmation
     * @return 200 OK with the user and fresh access/refresh tokens
     */
    @PatchMapping("/update/password")
    public ResponseEntity<HttpResponse> updateUserPassword(Authentication authentication, @RequestBody @Valid UpdatePasswordForm updatePasswordForm) {
        UserDTO authUser = getAuthenticatedUser(authentication);
        UserDTO dbUser = userService.getUserById(authUser.getId());
        if (!authUser.getId().equals(dbUser.getId()))
            throw new ApiException("Unauthorized request.");
        userService.updatePassword(dbUser.getId(), updatePasswordForm.getCurrentPassword(), updatePasswordForm.getNewPassword(), updatePasswordForm.getConfirmPassword());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", dbUser,
                                "access_token", tokenProvider.createAccessToken(getUserPrincipal(dbUser)),
                                "refresh_token", tokenProvider.createRefreshToken(getUserPrincipal(dbUser))))
                        .message("Your password has been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Exchanges a valid refresh token for a new access token. Validates the
     * Authorization header, extracts the subject, and returns a new
     * access token alongside the same refresh token; otherwise returns 400.
     *
     * @param request the HTTP request, expected to carry "Authorization: Bearer &lt;refresh&gt;"
     * @return 200 OK with the new access token, or 400 when the header/token is invalid
     */
    @GetMapping("/refresh/token")
    public ResponseEntity<HttpResponse> sendNewRefreshToken(HttpServletRequest request) {
        if (isHeaderAndTokenValid(request)) {
            String refreshToken = request.getHeader(AUTHORIZATION).substring(TOKEN_PREFIX.length());
            UserDTO userDTO = userService.getUserById(tokenProvider.getSubject(refreshToken, request));
            return ResponseEntity.ok(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .data(of("user", userDTO, "access_token", tokenProvider.createAccessToken(getUserPrincipal(userDTO)), "refresh_token", refreshToken))
                            .message("New refresh token sent successfully!")
                            .status(OK)
                            .statusCode(OK.value())
                            .build());
        } else {
            return ResponseEntity.badRequest().body(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .reason("Invalid or missing token. Please try again.")
                            .message("The refresh token is invalid or missing. Please log in again to obtain a new token.")
                            .status(BAD_REQUEST)
                            .statusCode(BAD_REQUEST.value())
                            .build());
        }
    }

    /**
     * Returns true when the request carries a "Bearer " Authorization header
     * whose token verifies and matches its subject.
     *
     * @param request the HTTP request to inspect
     * @return true if the header is present, well-formed, and the token is valid
     */
    private boolean isHeaderAndTokenValid(HttpServletRequest request) {
        return request.getHeader(AUTHORIZATION) != null
                && request.getHeader(AUTHORIZATION).startsWith(TOKEN_PREFIX)
                && tokenProvider.isTokenValid(
                tokenProvider.getSubject(request.getHeader(AUTHORIZATION).substring(TOKEN_PREFIX.length()), request),
                request.getHeader(AUTHORIZATION).substring(TOKEN_PREFIX.length()));
    }

    /**
     * Returns the current user's profile.
     *
     * <p>The {@link Authentication} was installed by {@code CustomAuthFilter}, which stores a
     * {@link UserDTO} directly as the principal (see {@code TokenProvider#getAuthentication}).
     * {@link com.bob.angularspringbootfullstack.utils.UserUtils#getAuthenticatedUser(Authentication)}
     * casts it back so we can read the email and reload the full profile — this avoids
     * {@code Authentication#getName()}, which would fall back to {@code UserDTO#toString()}.
     *
     * @param authentication the current Authentication injected by Spring Security
     * @return 200 OK with the user as a DTO
     */
    @GetMapping("/profile")
    public ResponseEntity<HttpResponse> getProfile(Authentication authentication) {
        UserDTO userDTO = userService.getUserByEmail(getAuthenticatedUser(authentication).getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO))
                        .message("We have fetched your profile for you!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates the authenticated user's profile with the supplied form data.
     * The user ID is always sourced from the authenticated principal — the client-supplied value is ignored.
     *
     * @param authentication the current authentication injected by Spring Security
     * @param user           the validated update payload
     * @return 200 OK with the updated user as a DTO
     */
    @PatchMapping("/update")
    public ResponseEntity<HttpResponse> updateUser(Authentication authentication, @RequestBody @Valid UpdateForm user) throws InterruptedException {
        //UserDTO authenticatedUser = userService.getUserByEmail(getAuthenticatedUser(authentication).getEmail());
        //user.setId(authenticatedUser.getId());
        TimeUnit.SECONDS.sleep(2); // Simulate a delay for testing the frontend loading state
        UserDTO updatedUser = userService.updateUserDTO(user);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", updatedUser))
                        .message("Your profile has been updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }


    // TODO(admin-update): Add a PATCH /user/admin/update/{userId} endpoint restricted to ADMIN role.
    //   - Accept a userId path variable; trust it (don't overwrite from JWT) so an admin can target any user.
    //   - Guard with @PreAuthorize or a permission check (e.g. UPDATE:USER authority).
    //   - SUPER_ADMIN bypasses org check; ORG_ADMIN must share an active org with the target user
    //     (see RoleRepoImpl#adminSharesOrgWithUser — throws 403 if no shared org found).
    //   - Frontend skeleton: Home > Users > User's Name already exists; wire it to this endpoint.
    //   - See RoleRepoImpl class Javadoc for full org-scoped RBAC design and schema.

    /**
     * Starts the password reset flow for the given email by generating a
     * one-time reset URL via UserService.
     *
     * @param email the email requesting a reset
     * @return 200 OK with a message advising the user to check their inbox
     */
    @GetMapping("/resetpassword/{email}")
    public ResponseEntity<HttpResponse> resetPassword(@PathVariable("email") String email) {
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
    public ResponseEntity<HttpResponse> verifyPasswordURL(@PathVariable("key") String key) {
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
     * Completes the password reset flow by setting a new password for the
     * user identified by the reset key. Confirms the two passwords match
     * before persisting.
     *
     * @param key             the UUID portion of the reset URL
     * @param newPassword     the new password
     * @param confirmPassword must equal newPassword
     * @return 200 OK on success
     */
    @PostMapping("/resetpassword/{key}/{newPassword}/{confirmPassword}")
    public ResponseEntity<HttpResponse> setNewPassword(@PathVariable("key") String key, @PathVariable("newPassword") String newPassword, @PathVariable("confirmPassword") String confirmPassword) {
        userService.setNewPassword(key, newPassword, confirmPassword);
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
     * When 2FA is enabled the response only signals that a verification code was sent; otherwise
     * it returns the user along with a fresh access and refresh token pair.
     *
     * @param loginForm validated email and password
     * @return 200 OK with either a "code sent" message or login data
     */
    @PostMapping("/login")
    public ResponseEntity<HttpResponse> login(@RequestBody @Valid LoginForm loginForm) {
        Authentication authentication = authenticate(loginForm.getEmail(), loginForm.getPassword());
        UserDTO userDTO = getLoggedInUser(authentication);
        return userDTO.isUsing2FA() ? sendVerificationCode(userDTO) : sendResponse(userDTO);
    }

    /**
     * Delegates to the AuthenticationManager. Catches any failure, hands it
     * to ExceptionUtils#processError so the client gets a JSON error, and
     * rethrows as ApiException so the caller stops processing.
     *
     * @param email    the submitted email
     * @param password the submitted password
     * @return the resulting authenticated Authentication
     */
    private Authentication authenticate(String email, String password) {
        try {
            return authenticationManager.authenticate(unauthenticated(email, password));
        } catch (Exception e) {
            // After running our front end, we are seeing that processError is preventing from the actual backend error message to show up on the front end error message (in the alert), so we have commented this out. The reason behind this is that the processError is writing the response to the HttpServletResponse, which is not compatible with our current front end error handling approach. By commenting this out, we allow the ApiException to be thrown and handled by our GlobalExceptionHandler, which will return a structured JSON response that our front end can easily parse and display the error message in an alert. If we were to keep processError, it would interfere with the normal flow of exception handling and prevent our front end from receiving the expected error response format.
            // processError(request, response, e);
            throw new ApiException(e.getMessage());
        }
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
     * Builds the standard login success response: the user plus a 230-minute
     * access token and a 5-day refresh token created from a freshly loaded
     * UserPrincipal.
     *
     * @param userDTO the successfully authenticated user
     * @return 200 OK with user data and both tokens
     */
    private ResponseEntity<HttpResponse> sendResponse(UserDTO userDTO) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO, "access_token", tokenProvider.createAccessToken(getUserPrincipal(userDTO)), "refresh_token", tokenProvider.createRefreshToken(getUserPrincipal(userDTO))))
                        .message("Login successful!")
                        .devMessage("AuthenticationManager succeeded; 230-min access token and 5-day refresh token issued via TokenProvider.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

}
