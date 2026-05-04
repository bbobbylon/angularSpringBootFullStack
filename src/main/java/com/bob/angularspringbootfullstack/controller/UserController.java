package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.form.LoginForm;
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

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.utils.ExceptionUtils.processError;
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
     *         verified or already verified
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
            UserDTO userDTO = userService.getUserByEmail(tokenProvider.getSubject(refreshToken, request));
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
     * Returns the current user's profile. Spring Security supplies the
     * Authentication populated by CustomAuthFilter; the email comes from the
     * token's subject and is used to look the user up.
     *
     * @param authentication the current Authentication injected by Spring Security
     * @return 200 OK with the user as a DTO
     */
    @GetMapping("/profile")
    public ResponseEntity<HttpResponse> getProfile(Authentication authentication) {
        UserDTO userDTO = userService.getUserByEmail(authentication.getName());
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
     * Authenticates a user by email and password. When 2FA is enabled the
     * response signals that a verification code was sent; otherwise it
     * returns the user along with a fresh access and refresh token.
     *
     * @param loginForm validated email and password
     * @return 200 OK with either a "code sent" message or login data
     */
    @PostMapping("/login")
    public ResponseEntity<HttpResponse> login(@RequestBody @Valid LoginForm loginForm) {
        Authentication authentication = authenticate(loginForm.getEmail(), loginForm.getPassword());
        UserDTO userDTO = getAuthenticatedUser(authentication);
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
            processError(request, response, e);
            throw new ApiException(e.getMessage());
        }
    }

    /**
     * Pulls the UserDTO out of an Authentication's principal (a UserPrincipal).
     *
     * @param authentication the Authentication produced by AuthenticationManager
     * @return the authenticated user as a DTO
     */
    private UserDTO getAuthenticatedUser(Authentication authentication) {
        return ((UserPrincipal) authentication.getPrincipal()).getUser();
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
     * Builds the standard login success response: the user plus a 30-minute
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
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

}
