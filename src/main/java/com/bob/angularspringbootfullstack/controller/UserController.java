package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.LoginForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final AuthenticationManager authenticationManager;

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
     *
     * @param userDTO the successfully authenticated user
     * @return ResponseEntity with HttpResponse indicating successful login
     */
    private ResponseEntity<HttpResponse> sendResponse(UserDTO userDTO) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userDTO))
                        .message("Login successful!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

}
