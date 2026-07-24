package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.ACCOUNT_DISABLED;
import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.ACCOUNT_LOCKED;
import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.BAD_PASSWORD;
import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.NO_ROLE_ASSIGNED;
import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.UNEXPECTED_ERROR;
import static com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger.LoginDenialReason.UNKNOWN_EMAIL;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link AuthDiagnosticsLogger} — no Spring context, no database, no HTTP.
 * <p>
 * The class under test is the console-only diagnostics seam that lets the sign-in path stay
 * vague to the client (one generic "Invalid email or password.") while the server records the
 * <em>true</em> reason. These tests lock in two things:
 * <ol>
 *   <li>{@link AuthDiagnosticsLogger#classify(UserDTO, Throwable)} maps every
 *       {@code (account resolved?) + (exception type)} pair to the correct
 *       {@link AuthDiagnosticsLogger.LoginDenialReason}, including the precedence rules that make
 *       the mapping unambiguous (state checks beat the null-account check; the null-account check
 *       beats the password check).</li>
 *   <li>The logging methods are null-tolerant, so a diagnostic call can never itself throw and
 *       turn a handled login failure into a 500.</li>
 * </ol>
 * The subtle assertion is {@code NO_ROLE_ASSIGNED}: a <em>known</em> account that surfaces a
 * {@link UsernameNotFoundException} is this codebase's fingerprint for the RBAC gap where an
 * identity authenticates but has no role/authorities to grant.
 */
class AuthDiagnosticsLoggerTest {

    /** Builds a fully-populated, good-standing account — the "resolved user" side of classify(). */
    private static UserDTO knownUser() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setEmail("real@example.com");
        user.setEnabled(true);
        user.setNotLocked(true);
        user.setRoleName("ROLE_USER");
        user.setPermissions("READ:USER,UPDATE:USER");
        return user;
    }

    @Test
    @DisplayName("null account (unknown email) → UNKNOWN_EMAIL")
    void classifiesUnknownEmail() {
        assertEquals(UNKNOWN_EMAIL,
                AuthDiagnosticsLogger.classify(null, new UsernameNotFoundException("no such user")));
    }

    @Test
    @DisplayName("known account + BadCredentialsException → BAD_PASSWORD")
    void classifiesBadPassword() {
        assertEquals(BAD_PASSWORD,
                AuthDiagnosticsLogger.classify(knownUser(), new BadCredentialsException("bad creds")));
    }

    @Test
    @DisplayName("DisabledException wins even when the account is unresolved (pre-auth state check)")
    void disabledBeatsNullAccount() {
        assertEquals(ACCOUNT_DISABLED,
                AuthDiagnosticsLogger.classify(null, new DisabledException("disabled")));
        assertEquals(ACCOUNT_DISABLED,
                AuthDiagnosticsLogger.classify(knownUser(), new DisabledException("disabled")));
    }

    @Test
    @DisplayName("LockedException → ACCOUNT_LOCKED")
    void classifiesLocked() {
        assertEquals(ACCOUNT_LOCKED,
                AuthDiagnosticsLogger.classify(knownUser(), new LockedException("locked")));
    }

    @Test
    @DisplayName("known account + UsernameNotFoundException → NO_ROLE_ASSIGNED (the RBAC gap)")
    void classifiesNoRoleAssigned() {
        assertEquals(NO_ROLE_ASSIGNED,
                AuthDiagnosticsLogger.classify(knownUser(), new UsernameNotFoundException("no role row")));
    }

    @Test
    @DisplayName("known account + unclassified exception → UNEXPECTED_ERROR")
    void classifiesUnexpected() {
        assertEquals(UNEXPECTED_ERROR,
                AuthDiagnosticsLogger.classify(knownUser(), new RuntimeException("boom")));
    }

    @Test
    @DisplayName("null account beats the password check: null + BadCredentials → UNKNOWN_EMAIL")
    void nullAccountBeatsBadPassword() {
        assertEquals(UNKNOWN_EMAIL,
                AuthDiagnosticsLogger.classify(null, new BadCredentialsException("bad creds")));
    }

    @Test
    @DisplayName("logDenied / logGranted / logAutoLock tolerate a null request without throwing")
    void loggingMethodsAreNullRequestSafe() {
        assertThatCode(() -> {
            AuthDiagnosticsLogger.logDenied("real@example.com", knownUser(), BAD_PASSWORD,
                    new BadCredentialsException("bad creds"), null);
            AuthDiagnosticsLogger.logDenied("ghost@example.com", null, UNKNOWN_EMAIL, null, null);
            AuthDiagnosticsLogger.logGranted("real@example.com", knownUser(), null);
            AuthDiagnosticsLogger.logAutoLock(knownUser(), 5, 15, null);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logForbidden reads the principal + authorities and does not throw")
    void logForbiddenSmokeTest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/user/delete/9");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                knownUser(), null, AuthorityUtils.createAuthorityList("READ:USER"));

        assertThatCode(() -> {
            AuthDiagnosticsLogger.logForbidden(auth, request);
            AuthDiagnosticsLogger.logForbidden(null, request); // anonymous branch
        }).doesNotThrowAnyException();
    }
}
