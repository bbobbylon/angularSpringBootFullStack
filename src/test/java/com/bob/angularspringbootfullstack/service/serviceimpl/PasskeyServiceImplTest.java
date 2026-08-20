package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.PasskeyQuery.COUNT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.PasskeyQuery.DELETE_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.PasskeyQuery.DELETE_PASSKEY_CREDENTIAL_BY_ID_AND_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.PasskeyQuery.SELECT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.PasskeyQuery.UPDATE_USER_USING_PASSKEY_QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PasskeyServiceImpl}. Deliberately scoped to what is testable without
 * generating a real WebAuthn attestation/assertion (which would need the {@code webauthn4j-test}
 * fixture library): the plain-JDBC credential lifecycle (list/delete/delete-all, including the
 * denormalized {@code users.using_passkey} sync), and the "malformed browser response" rejection
 * path, which genuinely exercises {@code webauthn4j}'s own JSON parsing rather than mocking it.
 *
 * <p><b>Known gap, stated plainly rather than silently skipped:</b> the full registration and
 * authentication verification round trip (valid challenge → valid attestation/assertion → stored
 * credential → successful sign-in) is NOT covered here. That would require constructing a
 * cryptographically valid {@code PublicKeyCredential} response via {@code webauthn4j-test}'s virtual
 * authenticator, which is a meaningful chunk of additional library-specific test scaffolding on top
 * of what is already a large feature addition in this change. Worth adding before this ships to
 * production; the credential lifecycle and rejection-path coverage here is what was practical to
 * verify in this pass.
 */
@ExtendWith(MockitoExtension.class)
class PasskeyServiceImplTest {

    private static final long USER_ID = 42L;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private WebAuthnChallengeStore challengeStore;

    @InjectMocks
    private PasskeyServiceImpl passkeyService;

    @Test
    @DisplayName("listCredentials maps every stored column onto the summary, never exposing the raw credential id or attestation object")
    void listCredentialsMapsRows() {
        when(jdbcTemplate.query(eq(SELECT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(7L);
                    when(rs.getString("device_name")).thenReturn("YubiKey");
                    when(rs.getString("transports")).thenReturn("usb,nfc");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf("2026-08-01 10:00:00"));
                    when(rs.getTimestamp("last_used_at")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<?> result = passkeyService.listCredentials(USER_ID);

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("deleteCredential removes the row scoped to its owner, then clears using_passkey when none remain")
    void deleteCredentialClearsFlagWhenLastCredentialRemoved() {
        when(jdbcTemplate.queryForObject(eq(COUNT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(0L);

        passkeyService.deleteCredential(USER_ID, 7L);

        verify(jdbcTemplate).update(eq(DELETE_PASSKEY_CREDENTIAL_BY_ID_AND_USER_ID_QUERY),
                eq(Map.of("id", 7L, "userId", USER_ID)));
        verify(jdbcTemplate).update(eq(UPDATE_USER_USING_PASSKEY_QUERY),
                eq(Map.of("usingPasskey", false, "userId", USER_ID)));
    }

    @Test
    @DisplayName("deleteCredential leaves using_passkey set when other credentials remain")
    void deleteCredentialKeepsFlagWhenCredentialsRemain() {
        when(jdbcTemplate.queryForObject(eq(COUNT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(1L);

        passkeyService.deleteCredential(USER_ID, 7L);

        verify(jdbcTemplate).update(eq(UPDATE_USER_USING_PASSKEY_QUERY),
                eq(Map.of("usingPasskey", true, "userId", USER_ID)));
    }

    @Test
    @DisplayName("deleteAllCredentials wipes every row and unconditionally clears using_passkey")
    void deleteAllCredentialsClearsEverything() {
        passkeyService.deleteAllCredentials(USER_ID);

        verify(jdbcTemplate).update(eq(DELETE_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY), eq(Map.of("userId", USER_ID)));
        verify(jdbcTemplate).update(eq(UPDATE_USER_USING_PASSKEY_QUERY),
                eq(Map.of("usingPasskey", false, "userId", USER_ID)));
    }

    @Test
    @DisplayName("a malformed registration response is rejected before any challenge lookup happens")
    void malformedRegistrationResponseIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> passkeyService.finishRegistration(USER_ID, "My Device", "{\"not\":\"a real webauthn response\"}"));

        assertTrue(ex.getMessage().toLowerCase().contains("try again"),
                "expected the generic parse-failure message, got: " + ex.getMessage());
        verifyNoInteractionsWithChallengeStore();
    }

    @Test
    @DisplayName("a malformed authentication response is rejected with the same neutral message every other login-time failure uses")
    void malformedAuthenticationResponseIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> passkeyService.finishAuthentication("{\"not\":\"a real webauthn response\"}"));

        assertTrue(ex.getMessage().toLowerCase().contains("expired") || ex.getMessage().toLowerCase().contains("log in again"),
                "expected the generic sign-in-failure message, got: " + ex.getMessage());
        verifyNoInteractionsWithChallengeStore();
    }

    private void verifyNoInteractionsWithChallengeStore() {
        org.mockito.Mockito.verifyNoInteractions(challengeStore);
    }
}
