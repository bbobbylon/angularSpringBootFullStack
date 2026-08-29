package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.webauthn4j.data.client.challenge.Challenge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.query.WebAuthnChallengeQuery.DELETE_CHALLENGE_QUERY;
import static com.bob.angularspringbootfullstack.query.WebAuthnChallengeQuery.DELETE_EXPIRED_CHALLENGES_QUERY;
import static com.bob.angularspringbootfullstack.query.WebAuthnChallengeQuery.INSERT_CHALLENGE_QUERY;
import static com.bob.angularspringbootfullstack.query.WebAuthnChallengeQuery.SELECT_CHALLENGE_QUERY;
import static com.bob.angularspringbootfullstack.service.serviceimpl.WebAuthnChallengeStore.Purpose.AUTHENTICATE;
import static com.bob.angularspringbootfullstack.service.serviceimpl.WebAuthnChallengeStore.Purpose.REGISTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DB-backed WebAuthn ceremony-challenge store (FUTURE-ENHANCEMENTS §2.4) —
 * structurally the same suite as {@link ProviderLinkTicketServiceTest}, applied to this class's
 * purpose-mismatch verdict instead of a provider mismatch, plus the AUTHENTICATE ceremony's
 * legitimately-null {@code userId}.
 */
@ExtendWith(MockitoExtension.class)
class WebAuthnChallengeStoreTest {

    private static final Long USER_ID = 7L;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private WebAuthnChallengeStore challengeStore;

    @BeforeEach
    void setUp() {
        challengeStore = new WebAuthnChallengeStore(jdbcTemplate);
    }

    /** Stubs the SELECT to return one row shaped by mapping the given values through the real RowMapper. */
    @SuppressWarnings("unchecked")
    private void stubSelectReturns(WebAuthnChallengeStore.Purpose purpose, Long userId, LocalDateTime expiresAt) throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getString("purpose")).thenReturn(purpose.name());
        when(rs.getObject("user_id", Long.class)).thenReturn(userId);
        when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.valueOf(expiresAt));

        when(jdbcTemplate.query(eq(SELECT_CHALLENGE_QUERY), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @SuppressWarnings("unchecked")
    private void stubSelectReturnsNothing() {
        when(jdbcTemplate.query(eq(SELECT_CHALLENGE_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("mintForRegistration purges expired rows, inserts a REGISTER row bound to the caller")
    void mintForRegistrationInsertsWithUserId() {
        Challenge challenge = challengeStore.mintForRegistration(USER_ID);

        assertTrue(challenge.getValue().length > 0);
        verify(jdbcTemplate).update(eq(DELETE_EXPIRED_CHALLENGES_QUERY), anyMap());
        verify(jdbcTemplate).update(eq(INSERT_CHALLENGE_QUERY), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("mintForAuthentication inserts an AUTHENTICATE row with no bound user")
    void mintForAuthenticationInsertsWithoutUserId() {
        challengeStore.mintForAuthentication();

        verify(jdbcTemplate).update(eq(INSERT_CHALLENGE_QUERY), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("redeeming an unknown challenge returns empty without touching the delete statement")
    void redeemUnknownChallengeReturnsEmpty() {
        stubSelectReturnsNothing();

        Optional<WebAuthnChallengeStore.RedeemedChallenge> result = challengeStore.redeem("nope", REGISTER);

        assertTrue(result.isEmpty());
        verify(jdbcTemplate, never()).update(eq(DELETE_CHALLENGE_QUERY), anyMap());
    }

    @Test
    @DisplayName("redeeming a blank or null challenge short-circuits before any database call")
    void redeemBlankChallengeShortCircuits() {
        assertTrue(challengeStore.redeem(null, REGISTER).isEmpty());
        assertTrue(challengeStore.redeem("   ", REGISTER).isEmpty());
        verify(jdbcTemplate, never()).query(eq(SELECT_CHALLENGE_QUERY), anyMap(), any(RowMapper.class));
    }

    @Test
    @DisplayName("redeeming an expired challenge deletes the row (housekeeping) and returns empty")
    void redeemExpiredChallengeDeletesAndReturnsEmpty() throws Exception {
        stubSelectReturns(REGISTER, USER_ID, LocalDateTime.now().minusMinutes(1));

        Optional<WebAuthnChallengeStore.RedeemedChallenge> result = challengeStore.redeem("some-challenge", REGISTER);

        assertTrue(result.isEmpty());
        verify(jdbcTemplate).update(eq(DELETE_CHALLENGE_QUERY), anyMap());
    }

    @Test
    @DisplayName("a purpose mismatch is refused WITHOUT consuming the challenge")
    void redeemPurposeMismatchDoesNotConsume() throws Exception {
        stubSelectReturns(REGISTER, USER_ID, LocalDateTime.now().plusMinutes(4));

        Optional<WebAuthnChallengeStore.RedeemedChallenge> result = challengeStore.redeem("some-challenge", AUTHENTICATE);

        assertTrue(result.isEmpty());
        verify(jdbcTemplate, never()).update(eq(DELETE_CHALLENGE_QUERY), anyMap());
    }

    @Test
    @DisplayName("a concurrently-consumed challenge (delete affects zero rows) is reported as empty")
    void redeemConcurrentlyConsumedChallengeReturnsEmpty() throws Exception {
        stubSelectReturns(AUTHENTICATE, null, LocalDateTime.now().plusMinutes(4));
        when(jdbcTemplate.update(eq(DELETE_CHALLENGE_QUERY), anyMap())).thenReturn(0);

        Optional<WebAuthnChallengeStore.RedeemedChallenge> result = challengeStore.redeem("some-challenge", AUTHENTICATE);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("a valid AUTHENTICATE redemption succeeds with a null userId, not a thrown NPE")
    void redeemAuthenticateHappyPathHasNullUserId() throws Exception {
        stubSelectReturns(AUTHENTICATE, null, LocalDateTime.now().plusMinutes(4));
        when(jdbcTemplate.update(eq(DELETE_CHALLENGE_QUERY), anyMap())).thenReturn(1);

        Optional<WebAuthnChallengeStore.RedeemedChallenge> result = challengeStore.redeem("c2hhbGxlbmdl", AUTHENTICATE);

        assertTrue(result.isPresent());
        assertNull(result.get().userId());
    }

    @Test
    @DisplayName("a valid REGISTER redemption returns the bound userId and a Challenge round-tripped from the stored key")
    void redeemRegisterHappyPathReturnsBoundUserId() throws Exception {
        stubSelectReturns(REGISTER, USER_ID, LocalDateTime.now().plusMinutes(4));
        when(jdbcTemplate.update(eq(DELETE_CHALLENGE_QUERY), anyMap())).thenReturn(1);
        // Only the encoding is needed here, not a real mint — going through mintForRegistration
        // would trip purgeExpired()'s own (separately-tested, here-unstubbed) DELETE statement.
        String key = WebAuthnChallengeStore.encodeChallenge(new com.webauthn4j.data.client.challenge.DefaultChallenge());

        Optional<WebAuthnChallengeStore.RedeemedChallenge> result = challengeStore.redeem(key, REGISTER);

        assertTrue(result.isPresent());
        assertEquals(USER_ID, result.get().userId());
        // The redeemed Challenge round-trips through the same base64url encoding it was looked up
        // by — DefaultChallenge's String constructor decodes with webauthn4j's own Base64UrlUtil,
        // the same codec encodeChallenge uses, so this must be lossless.
        assertEquals(key, WebAuthnChallengeStore.encodeChallenge(result.get().challenge()));
    }
}
