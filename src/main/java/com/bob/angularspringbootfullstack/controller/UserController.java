package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.LoginForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {
    private final UserService userService;
    private final RoleService roleService;
    private final AuthenticationManager authenticationManager;
    private final TokenProvider tokenProvider;

    /**
     * Registers a new user in the system.
     * Validates the incoming user data, creates the user via the UserService,
     * and returns a 201 CREATED response with the newly created UserDTO.
     *
     * @param user the user registration data (validated with @Valid)
     * @return ResponseEntity with HttpResponse containing the created user data and CREATED status
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
     * Constructs a URI for the registered user resource.
     * This URI is used in the Location header of the 201 CREATED response.
     *
     * @return a URI pointing to the user resource (e.g., /user/get/<userId>)
     */
    private URI getUri() {
        return URI.create(ServletUriComponentsBuilder.fromCurrentContextPath().path("/user/get/<userId>").toUriString());
    }

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
     * Creates a UserPrincipal object from a UserDTO.
     * <p>
     * UserPrincipal is Spring Security's representation of an authenticated user.
     * It implements the UserDetails interface and is needed for:
     * - Creating JWT tokens (TokenProvider.createAccessToken() expects UserPrincipal)
     * - Spring Security context (stores UserPrincipal in SecurityContextHolder)
     * - Authorization checks (contains user's authorities/permissions)
     * <p>
     * Process:
     * 1. Gets full User entity from database:
     * - userService.getUser(userDTO.getEmail())
     * - Queries: SELECT * FROM users WHERE email = ?
     * - Returns complete User object with all fields
     * <p>
     * 2. Gets user's role and permissions:
     * - roleService.getRoleByUserId(userDTO.getId())
     * - Queries: SELECT role WHERE user_id = ?
     * - Returns Role object containing permission string
     * <p>
     * 3. Extracts permissions from role:
     * - roleService.getRoleByUserId(...).getPermission()
     * - Example: "READ:USER,UPDATE:USER,DELETE:USER"
     * <p>
     * 4. Creates UserPrincipal with User and permissions:
     * - UserPrincipal(user, permissions)
     * - UserPrincipal converts permission string to List<GrantedAuthority>
     * - Each permission becomes a SimpleGrantedAuthority
     * <p>
     * Why we need this:
     * - JWT token creation requires UserPrincipal to extract authorities
     * - TokenProvider.createAccessToken(userPrincipal) calls:
     * getClaimsFromUser(userPrincipal) which calls userPrincipal.getAuthorities()
     * which parses the permission string and creates SimpleGrantedAuthority objects
     * - These authorities are embedded in the JWT token as a claim
     * - When token is verified later, authorities are extracted and used for authorization
     * We also switched from using the User entity to the UserDTO for security and performance reasons. One being that we want to keep the User separate in terms of not having that User object being sent all the way up the chain to the controller. Another being that we want to keep the UserDTO lightweight and only contain the necessary information for the controller to function (it doesn't contain the user's password).
     *
     * @param userDTO the authenticated user DTO (has id and email)
     * @return UserPrincipal object with User entity and authority permissions
     */
    private UserPrincipal getUserPrincipal(UserDTO userDTO) {
        return new UserPrincipal(toUser(userService.getUserByEmail(userDTO.getEmail())), roleService.getRoleByUserId(userDTO.getId()).getPermission());
    }

    /**
     * Retrieves the authenticated user's profile information.
     * <p>
     * This endpoint is protected by Spring Security and requires a valid JWT token.
     * The Authentication object is automatically injected by Spring Security's
     * SecurityContextHolder, which is populated by CustomAuthFilter during request processing.
     * <p>
     * Flow:
     * 1. Extracts user email from Authentication.getName() (set by TokenProvider)
     * 2. Calls UserService.getUserByEmail() to fetch UserDTO from the database
     * 3. Casts Authentication.getPrincipal() to UserPrincipal (created by TokenProvider)
     * 4. Logs debug information about the authenticated user and their authorities
     * 5. Returns UserDTO in HttpResponse wrapper
     * <p>
     * Integration points:
     * - CustomAuthFilter: Validates JWT and sets Authentication in SecurityContext
     * - TokenProvider: Creates UserPrincipal with User entity and permissions
     * - UserService: Provides UserDTO via database lookup
     * - UserPrincipal: Contains the full User entity and GrantedAuthority list
     *
     * @param authentication Spring Security Authentication object containing user details
     * @return ResponseEntity with HttpResponse containing UserDTO and success message
     */
    @GetMapping("/profile")
    public ResponseEntity<HttpResponse> getProfile(Authentication authentication) {
        UserDTO userDTO = userService.getUserByEmail(authentication.getName());
        //System.out.println(authentication.getPrincipal());
        // info for the currently logged-in user.
        System.out.println(authentication);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO))
                        .message("We have fetched your profile for you!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @RequestMapping("/error")
    public ResponseEntity<HttpResponse> errorHandling(HttpServletRequest request) {
        log.info(String.valueOf(request));
        System.out.println(request.getRequestURI());
        return ResponseEntity.badRequest().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("An unknown error has occurred. There is no mapping for a " + request.getMethod() + "request for this path on our server. Sorry! Please try something else.")
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value())
                        .build());
    }

    /**
     * Authenticates a user and initiates the login process.
     * Validates the login credentials using AuthenticationManager. If 2FA is enabled,
     * sends a verification code. Otherwise, returns a successful login response.
     * <p>
     * The authentication flow:
     * 1. AuthenticationManager.authenticate() receives a UsernamePasswordAuthenticationToken
     * 2. It passes through the authentication provider chain (DaoAuthenticationProvider, etc.)
     * 3. If successful, the user is authenticated
     * 4. UserDTO is retrieved and checked for 2FA status
     * 5. Either a verification code is sent (2FA enabled) or a direct login response (2FA disabled)
     *
     * @param loginForm the login credentials containing email and password
     * @return ResponseEntity with HttpResponse indicating either 2FA code sent or successful login
     */
    @PostMapping("/login")
    public ResponseEntity<HttpResponse> login(@RequestBody @Valid LoginForm loginForm) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginForm.getEmail(), loginForm.getPassword()));
        UserDTO userDTO = userService.getUserByEmail(loginForm.getEmail());
        return userDTO.isUsing2FA() ? sendVerificationCode(userDTO) : sendResponse(userDTO);
    }

    /**
     * Sends a 2FA verification code to the user via their registered phone number or email.
     * This method is called when a user with 2FA enabled attempts to log in.
     * The verification code must be validated before completing the authentication.
     *
     * @param userDTO the authenticated user (with 2FA enabled)
     * @return ResponseEntity with HttpResponse indicating the 2FA code has been sent
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
     * Sends a successful login response to users without 2FA enabled.
     * This method completes the authentication for users who don't have
     * two-factor authentication enabled.
     * <p>
     * Response includes:
     * - User DTO with profile information (password excluded)
     * - Access token: JWT token valid for 30 minutes, used to authenticate subsequent requests
     * - Refresh token: JWT token valid for 5 days, used to obtain a new access token when it expires
     * <p>
     * Token generation flow:
     * 1. getUserPrincipal(userDTO) is called to create UserPrincipal with:
     * - User entity (from database lookup by email)
     * - Authorities (from user's role permissions)
     * 2. Access token is created with:
     * - Subject: user's email
     * - Authorities claim: comma-separated permissions
     * - Expiration: 30 minutes from now
     * 3. Refresh token is created with:
     * - Subject: user's email
     * - No authorities claim (refresh tokens don't need permissions)
     * - Expiration: 5 days from now
     * <p>
     * The tokens are returned to the frontend in the response data map,
     * so the frontend can store them (typically in localStorage) and include
     * them in Authorization headers for subsequent API requests.
     *
     * @param userDTO the successfully authenticated user
     * @return ResponseEntity with HttpResponse containing user data and JWT tokens
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
