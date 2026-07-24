package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.TotpQuery.DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY;
import static com.bob.angularspringbootfullstack.query.TotpQuery.SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private TotpServiceImpl totpService;

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
}
