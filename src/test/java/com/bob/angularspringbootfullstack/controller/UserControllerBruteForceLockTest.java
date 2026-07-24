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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for the brute-force <em>persistent-lock</em> hardening (plan.md M6) on
 * {@code POST /user/login}.
 * <p>
 * The pre-existing sliding-window guard only rejected the current attempt; once the window rolled
 * off, the account recovered on its own. This test locks in the stronger rule: crossing the failure
 * threshold flips {@code notLocked = false} so the account stays locked until an administrator
 * unlocks it — while the write is idempotent (an already-locked account is not re-written on every
 * subsequent hammering attempt).
 * <p>
 * Like {@code UserControllerLoginEnumerationTest}, this uses {@link MockMvcBuilders#standaloneSetup}
 * with Mockito-mocked collaborators and the real {@link GlobalExceptionHandler}, so it exercises the
 * genuine controller logic with no Spring context, security filter chain, or datasource — it runs in
 * milliseconds in CI without MySQL. The account-state write is asserted via a Mockito verification on
 * {@code UserService.updateAccountSettings}, which is exactly the seam the controller uses to lock.
 */
class UserControllerBruteForceLockTest {

    private static final String KNOWN_EMAIL = "victim@example.com";
    private static final long VICTIM_ID = 42L;
    private static final String LOGIN_BODY =
            "{\"email\":\"" + KNOWN_EMAIL + "\",\"password\":\"whatever123\"}";

    private UserService userService;
    private EventService eventService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        eventService = mock(EventService.class);
        RoleService roleService = mock(RoleService.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TotpService totpService = mock(TotpService.class);
        SessionService sessionService = mock(SessionService.class);

        UserController controller = new UserController(userService, roleService, authenticationManager,
                request, eventPublisher, eventService, totpService, sessionService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** A known, good-standing account that is currently unlocked. */
    private static UserDTO unlockedVictim() {
        UserDTO victim = new UserDTO();
        victim.setId(VICTIM_ID);
        victim.setEmail(KNOWN_EMAIL);
        victim.setEnabled(true);
        victim.setNotLocked(true);
        return victim;
    }

    @Test
    @DisplayName("crossing the failure threshold locks an unlocked account (notLocked=false), preserving enabled")
    void bruteForceLocksUnlockedAccount() throws Exception {
        when(userService.getUserByEmail(KNOWN_EMAIL)).thenReturn(unlockedVictim());
        // At/over the threshold: the brute-force branch fires before the AuthenticationManager is called.
        when(eventService.countRecentFailuresByEmail(eq(KNOWN_EMAIL), anyInt())).thenReturn(5L);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isBadRequest());

        // The lock is persisted exactly once, with enabled preserved and notLocked forced false.
        verify(userService).updateAccountSettings(VICTIM_ID, true, false);
    }

    @Test
    @DisplayName("an already-locked account is not re-locked (idempotent, no redundant write)")
    void alreadyLockedAccountIsNotRewritten() throws Exception {
        UserDTO alreadyLocked = unlockedVictim();
        alreadyLocked.setNotLocked(false); // already locked from a prior lockout
        when(userService.getUserByEmail(KNOWN_EMAIL)).thenReturn(alreadyLocked);
        when(eventService.countRecentFailuresByEmail(eq(KNOWN_EMAIL), anyInt())).thenReturn(9L);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateAccountSettings(anyLong(), any(), any());
    }
}
