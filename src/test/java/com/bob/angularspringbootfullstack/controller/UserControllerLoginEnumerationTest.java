package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for the login anti-enumeration contract (FR-AUTH-4, NFR-SEC-7), the fix
 * landed in commit 353f1b5. It locks in the rule that {@code POST /user/login} MUST be unable
 * to tell a caller whether an email is registered: an <em>unknown email</em> and a
 * <em>wrong password for a known account</em> have to produce a byte-identical response.
 * <p>
 * The two cases travel <b>different internal paths</b> inside
 * {@link UserController#authenticate(String, String)} on purpose:
 * <ul>
 *   <li><b>Unknown email</b> — {@code UserService.getUserByEmail} raises
 *       {@link UsernameNotFoundException}, which {@code findUserOrNull} swallows to {@code null};
 *       no {@code LOGIN_ATTEMPT} / {@code LOGIN_ATTEMPT_FAILURE} audit events are published
 *       (FR-AUDIT-3), so the audit log is not an enumeration oracle either.</li>
 *   <li><b>Wrong password</b> — the account resolves, audit events <em>do</em> fire, then the
 *       {@code AuthenticationManager} rejects the credentials.</li>
 * </ul>
 * Both funnel into the controller's generic {@code catch} and are rethrown as one
 * {@code ApiException("Invalid email or password.")}, which {@link GlobalExceptionHandler}
 * renders as an identical 400 envelope. This test asserts that identity at two levels: the
 * individual envelope fields, and the full response body (minus the per-call timestamp).
 * <p>
 * Like {@code GlobalExceptionHandlerTest}, it uses {@link MockMvcBuilders#standaloneSetup} with
 * Mockito-mocked collaborators and the real advice, so it exercises the genuine
 * controller-to-envelope mapping without booting the Spring context, the JWT security filter
 * chain, or a datasource — it runs in milliseconds in any environment, including CI with no MySQL.
 */
class UserControllerLoginEnumerationTest {

    /** A syntactically valid email with no matching account — the unknown-email case. */
    private static final String UNKNOWN_EMAIL = "ghost@example.com";
    /** A syntactically valid email that DOES resolve to an account — the wrong-password case. */
    private static final String KNOWN_EMAIL = "real@example.com";

    private static final String UNKNOWN_EMAIL_BODY =
            "{\"email\":\"" + UNKNOWN_EMAIL + "\",\"password\":\"whatever123\"}";
    private static final String WRONG_PASSWORD_BODY =
            "{\"email\":\"" + KNOWN_EMAIL + "\",\"password\":\"wrongpassword\"}";

    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        RoleService roleService = mock(RoleService.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        EventService eventService = mock(EventService.class);
        TotpService totpService = mock(TotpService.class);
        SessionService sessionService = mock(SessionService.class);

        UserController controller = new UserController(userService, roleService, authenticationManager,
                request, eventPublisher, eventService, totpService, sessionService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // Whether the email is known or not, the credential check ultimately fails the same way:
        // the AuthenticationManager rejects the (unauthenticated) token. This is what makes the
        // wrong-password and unknown-email cases converge.
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        // Unknown email: the repository raises UsernameNotFoundException (swallowed to null upstream).
        when(userService.getUserByEmail(UNKNOWN_EMAIL))
                .thenThrow(new UsernameNotFoundException("No user found by email: " + UNKNOWN_EMAIL));
        // Known email: resolves to a user, so the failure path additionally publishes audit events.
        when(userService.getUserByEmail(KNOWN_EMAIL)).thenReturn(new UserDTO());
    }

    @Test
    @DisplayName("Unknown email and wrong password both → 400 with the identical generic message")
    void bothFailureModesReturnSameStatusAndMessage() throws Exception {
        for (String body : new String[]{UNKNOWN_EMAIL_BODY, WRONG_PASSWORD_BODY}) {
            mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.statusCode", is(400)))
                    // HttpStatus serializes via its enum toString() — "400 BAD_REQUEST", not just the name.
                    .andExpect(jsonPath("$.status", is("400 BAD_REQUEST")))
                    .andExpect(jsonPath("$.reason", is("Invalid email or password.")))
                    .andExpect(jsonPath("$.devMessage", is("Invalid email or password.")));
        }
    }

    @Test
    @DisplayName("Response bodies are identical except for the timestamp (no enumeration oracle)")
    void responseBodiesAreIndistinguishableExceptTimestamp() throws Exception {
        String unknownEmailResponse = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UNKNOWN_EMAIL_BODY))
                .andReturn().getResponse().getContentAsString();

        String wrongPasswordResponse = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WRONG_PASSWORD_BODY))
                .andReturn().getResponse().getContentAsString();

        assertEquals(stripTimestamp(unknownEmailResponse), stripTimestamp(wrongPasswordResponse),
                "Unknown-email and wrong-password responses must be indistinguishable (FR-AUTH-4, NFR-SEC-7)");
    }

    /**
     * Removes the per-call {@code timeStamp} field, the only legitimately-varying part of the
     * envelope, so the equality assertion reflects exactly the content a caller could use to
     * distinguish the two failure modes.
     */
    private static String stripTimestamp(String json) {
        return json.replaceAll("\"timeStamp\":\"[^\"]*\",?", "");
    }
}
