package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.TotpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.TotpQuery.CONSUME_RECOVERY_CODE_QUERY;
import static com.bob.angularspringbootfullstack.query.TotpQuery.DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY;
import static com.bob.angularspringbootfullstack.query.TotpQuery.SELECT_TOTP_CREDENTIAL_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.TotpQuery.SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the TOTP <em>login-challenge binding</em> (SRS FR-MFA-4), the property that makes a
 * public {@code POST /user/verify/totp} endpoint safe: a valid authenticator code alone must not
 * complete a login — it must be paired with a live, server-side challenge that was minted only after
 * a successful FIRST factor. Without that binding, anyone holding the authenticator could skip the
 * password step entirely.
 * <p>
 * These tests mock the {@link NamedParameterJdbcTemplate} and drive
 * {@link TotpServiceImpl#verifyLoginChallenge(String, String)} down its rejection path: when the
 * live-challenge lookup returns nothing (forged, already-consumed, or expired challenge), the call is
 * refused with a neutral {@link ApiException} and — critically — the challenge is NOT deleted, so no
 * state is mutated by an unauthenticated probe. The lookup returns empty before the TOTP code is ever
 * checked, so no static crypto mocking is needed.
 */
@ExtendWith(MockitoExtension.class)
class TotpServiceImplTest {

    private static final String CHALLENGE = "challenge-uuid";
    private static final long CHALLENGE_OWNER = 42L;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private TotpServiceImpl totpService;

    /**
     * Makes the challenge lookup resolve to {@link #CHALLENGE_OWNER} and gives that account a
     * confirmed authenticator credential.
     *
     * <p>The secret is deliberate nonsense: every test that reaches the code check drives the
     * <em>recovery-code</em> branch, so {@code TotpUtils.verifyCode} is always expected to fail.
     * That keeps these tests entirely free of TOTP time-window arithmetic and static mocking —
     * the property under test is the challenge binding and the consume-exactly-once rule, neither
     * of which is about the crypto.
     */
    private void stubLiveChallengeFor(long userId) throws Exception {
        when(jdbcTemplate.queryForList(eq(SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(List.of(userId));

        // The credential row maps into a record that is private to the service, so the test cannot
        // build one directly. Instead it hands the *production* RowMapper a stubbed ResultSet and
        // returns whatever that produces — the object under test is therefore constructed by the
        // real mapping code, not by a test-only stand-in that could drift from it.
        when(jdbcTemplate.query(eq(SELECT_TOTP_CREDENTIAL_BY_USER_ID_QUERY), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("secret")).thenReturn("JBSWY3DPEHPK3PXP");
                    when(resultSet.getBoolean("confirmed")).thenReturn(true);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }

    @Test
    @DisplayName("a forged/expired challenge is refused and no challenge row is consumed")
    void forgedChallengeIsRejectedWithoutConsuming() {
        // No live challenge matches → the SQL-level expiry/existence check returns no user id.
        when(jdbcTemplate.queryForList(eq(SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(List.of());

        assertThrows(ApiException.class, () -> totpService.verifyLoginChallenge("forged-challenge-uuid", "123456"));

        // The challenge must not be deleted — nothing gets consumed by an attempt that never bound
        // to a completed first factor.
        verify(jdbcTemplate, never()).update(eq(DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY), anyMap());
    }

    @Test
    @DisplayName("a null challenge is treated as no-match and refused")
    void nullChallengeIsRejected() {
        when(jdbcTemplate.queryForList(eq(SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(List.of());

        assertThrows(ApiException.class, () -> totpService.verifyLoginChallenge(null, "123456"));
    }

    @Test
    @DisplayName("the verified identity comes from the CHALLENGE, never from the caller")
    void identityIsTakenFromTheChallengeNotTheRequest() throws Exception {
        // The whole point of challenge binding. POST /user/verify/totp is a PUBLIC endpoint and its
        // body carries only {challenge, code} — no user id, no email. The account being signed in
        // is resolved server-side from the challenge row, which was minted only after a successful
        // first factor. If the identity could come from anywhere else, a valid authenticator code
        // would be enough to sign in as somebody else.
        stubLiveChallengeFor(CHALLENGE_OWNER);
        // The 6-digit code will not match the stubbed secret, so the recovery-code branch runs and
        // is stubbed to succeed — this test is about WHOSE account is returned, not which factor.
        when(jdbcTemplate.update(eq(CONSUME_RECOVERY_CODE_QUERY), anyMap())).thenReturn(1);

        TotpService.TotpVerification verification = totpService.verifyLoginChallenge(CHALLENGE, "some-recovery-code");

        assertEquals(CHALLENGE_OWNER, verification.userId());
        assertTrue(verification.usedRecoveryCode());
        // A completed verification burns the challenge, so the same one cannot be replayed.
        verify(jdbcTemplate).update(eq(DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY), anyMap());
    }

    @Test
    @DisplayName("a wrong code refuses but keeps the challenge alive for a retry")
    void wrongCodeDoesNotBurnTheChallenge() throws Exception {
        stubLiveChallengeFor(CHALLENGE_OWNER);
        // Neither the authenticator code nor any recovery code matches.
        when(jdbcTemplate.update(eq(CONSUME_RECOVERY_CODE_QUERY), anyMap())).thenReturn(0);

        assertThrows(ApiException.class, () -> totpService.verifyLoginChallenge(CHALLENGE, "000000"));

        // Deleting the challenge on a wrong guess would turn a typo into a forced re-login, and
        // would hand anyone who can reach the endpoint a way to cancel someone else's in-flight
        // sign-in by spraying wrong codes at a challenge they do not own.
        verify(jdbcTemplate, never()).update(eq(DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY), anyMap());
    }

    @Test
    @DisplayName("a recovery code is consumed by the same statement that validates it")
    void recoveryCodeCheckAndConsumeIsAtomic() throws Exception {
        stubLiveChallengeFor(CHALLENGE_OWNER);
        when(jdbcTemplate.update(eq(CONSUME_RECOVERY_CODE_QUERY), anyMap())).thenReturn(1);

        totpService.verifyLoginChallenge(CHALLENGE, "recovery-code");

        // Exactly one consume attempt: validation and consumption are a single UPDATE whose
        // affected-row count IS the verdict. A separate "check then burn" pair would leave a window
        // in which two concurrent requests could both pass the check and spend the same code twice.
        verify(jdbcTemplate).update(eq(CONSUME_RECOVERY_CODE_QUERY), anyMap());
    }
}
