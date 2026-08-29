package com.bob.angularspringbootfullstack.tokenprovider;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static com.bob.angularspringbootfullstack.constants.Constants.BOBBYLON_LLC;
import static com.bob.angularspringbootfullstack.constants.Constants.BOBS_MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenProvider#isTokenValid}, pinning down the role-change invalidation
 * check added alongside the pre-existing password-change one (FUTURE-ENHANCEMENTS §3.1 — role-
 * change JWT staleness). The property under test: a token must be issued after BOTH the user's
 * last password change and last role change, not merely the more recent of the two — two
 * independent gates, not one gate fed by whichever timestamp happens to be newer.
 *
 * <p>Tokens are hand-signed with the exact same issuer/audience/algorithm
 * {@link TokenProvider#createAccessToken} uses, so {@code isTokenValid} verifies them through its
 * real signature-checking path rather than a stub — only {@code issuedAt} varies per test. The
 * {@code secret} field (populated by {@code @Value} in production) is set via
 * {@link ReflectionTestUtils} since this class has no test-visible setter for it.
 */
@ExtendWith(MockitoExtension.class)
class TokenProviderTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret";
    private static final Long USER_ID = 7L;

    @Mock
    private UserService userService;

    private TokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new TokenProvider(userService);
        ReflectionTestUtils.setField(tokenProvider, "secret", SECRET);
    }

    private void stubUser(LocalDateTime passwordChangedAt, LocalDateTime rolesChangedAt) {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setPasswordChangedAt(passwordChangedAt);
        user.setRolesChangedAt(rolesChangedAt);
        when(userService.getUserById(USER_ID)).thenReturn(user);
    }

    /** Signs a token exactly the way {@link TokenProvider#createAccessToken} does, with a controlled issuedAt. */
    private String tokenIssuedAt(LocalDateTime issuedAt) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(Date.from(issuedAt.atZone(ZoneId.systemDefault()).toInstant()))
                .withSubject(String.valueOf(USER_ID))
                .withExpiresAt(Date.from(issuedAt.plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant()))
                .sign(Algorithm.HMAC512(SECRET));
    }

    @Test
    @DisplayName("neither password nor role ever changed: any unexpired token is valid")
    void neitherChangedIsAlwaysValid() {
        stubUser(null, null);

        assertTrue(tokenProvider.isTokenValid(USER_ID, tokenIssuedAt(LocalDateTime.now().minusMinutes(20))));
    }

    @Test
    @DisplayName("a token issued before a password change is rejected, regardless of roles_changed_at")
    void tokenBeforePasswordChangeIsInvalid() {
        LocalDateTime passwordChangedAt = LocalDateTime.now().minusMinutes(10);
        stubUser(passwordChangedAt, null);

        assertFalse(tokenProvider.isTokenValid(USER_ID, tokenIssuedAt(passwordChangedAt.minusMinutes(1))));
    }

    @Test
    @DisplayName("a token issued before a role change is rejected, regardless of password_changed_at — the new check")
    void tokenBeforeRoleChangeIsInvalid() {
        LocalDateTime rolesChangedAt = LocalDateTime.now().minusMinutes(10);
        stubUser(null, rolesChangedAt);

        assertFalse(tokenProvider.isTokenValid(USER_ID, tokenIssuedAt(rolesChangedAt.minusMinutes(1))));
    }

    @Test
    @DisplayName("a token must postdate BOTH events — issued after the older one is still invalid if it predates the newer one")
    void tokenMustPostdateBothEvents() {
        LocalDateTime passwordChangedAt = LocalDateTime.now().minusMinutes(20);
        LocalDateTime rolesChangedAt = LocalDateTime.now().minusMinutes(5);
        stubUser(passwordChangedAt, rolesChangedAt);

        // Issued after the password change but before the (later) role change.
        assertFalse(tokenProvider.isTokenValid(USER_ID, tokenIssuedAt(passwordChangedAt.plusMinutes(1))));
    }

    @Test
    @DisplayName("a token issued after both events is valid")
    void tokenAfterBothEventsIsValid() {
        LocalDateTime passwordChangedAt = LocalDateTime.now().minusMinutes(20);
        LocalDateTime rolesChangedAt = LocalDateTime.now().minusMinutes(10);
        stubUser(passwordChangedAt, rolesChangedAt);

        assertTrue(tokenProvider.isTokenValid(USER_ID, tokenIssuedAt(rolesChangedAt.plusMinutes(1))));
    }

    @Test
    @DisplayName("a null userId is invalid without ever looking the user up")
    void nullUserIdIsInvalid() {
        assertFalse(tokenProvider.isTokenValid(null, tokenIssuedAt(LocalDateTime.now())));
    }
}
