package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.LoginRiskService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.utils.TurnstileUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-boundary test for the Turnstile CAPTCHA gate on {@code POST /user/register}
 * (FUTURE-ENHANCEMENTS.md §3.1, {@link TurnstileUtils}).
 * <p>
 * Only the graceful-degradation contract is exercised here: {@link TurnstileUtils#SECRET_KEY} is a
 * {@code static final} field bound once from {@code System.getenv("TURNSTILE_SECRET_KEY")} at
 * class-load time, so — exactly like {@code UserControllerLoginEnumerationTest}'s Twilio-adjacent
 * neighbors — no test in this JVM can flip it to "configured" to also exercise the rejection path
 * at the controller level. {@code TurnstileUtilsTest} covers every branch of the decision logic
 * directly; this test only proves the wiring in {@code UserController#saveUser} does not block
 * registration in the unconfigured state every dev/CI environment actually runs in.
 */
class UserControllerRegistrationCaptchaTest {

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
        LoginRiskService loginRiskService = mock(LoginRiskService.class);

        UserController controller = new UserController(userService, roleService, authenticationManager,
                request, eventPublisher, eventService, totpService, sessionService, loginRiskService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UserDTO created = new UserDTO();
        created.setEmail("new.user@example.com");
        when(userService.createUser(any())).thenReturn(created);
    }

    @Test
    @DisplayName("Turnstile unconfigured (no TURNSTILE_SECRET_KEY set): registration still succeeds with no token header")
    void registrationSucceedsWithoutCaptchaWhenUnconfigured() throws Exception {
        // Documents the precondition this test actually relies on — see the class Javadoc.
        assertTrue(!TurnstileUtils.isConfigured(), "This test assumes TURNSTILE_SECRET_KEY is unset in the test environment");

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"New\",\"lastName\":\"User\",\"email\":\"new.user@example.com\",\"password\":\"Whatever123\"}"))
                .andExpect(status().isCreated());
    }
}
