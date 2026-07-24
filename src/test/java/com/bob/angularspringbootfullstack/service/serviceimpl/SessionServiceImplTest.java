package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.RefreshSession;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.SessionQuery.INSERT_SESSION_QUERY;
import static com.bob.angularspringbootfullstack.query.SessionQuery.REVOKE_FAMILY_QUERY;
import static com.bob.angularspringbootfullstack.query.SessionQuery.SELECT_SESSION_BY_JTI_QUERY;
import static com.bob.angularspringbootfullstack.query.SessionQuery.SUPERSEDE_SESSION_QUERY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the refresh-token rotation store (SRS FR-JWT-5, plan.md M5), focused on the two
 * fail-closed verdicts that make sliding sessions safe. The {@link NamedParameterJdbcTemplate} and
 * {@link TokenProvider} are mocked, so no database or real JWTs are involved.
 * <p>
 * The critical case is <b>reuse detection</b>: presenting a refresh token whose session row is already
 * {@code superseded} (the signature of a stolen, replayed token) must revoke the <em>entire family</em>
 * and refuse — it must NOT rotate. If rotation proceeded, a thief who replayed an old token would be
 * handed a brand-new valid token. The second case guards the unknown-jti path: a structurally valid
 * but unrecognized token performs no writes at all.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceImplTest {

    private static final String REFRESH_TOKEN = "refresh.jwt.value";
    private static final long USER_ID = 7L;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private UserService userService;
    @Mock
    private RoleService roleService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private SessionServiceImpl sessionService;

    @BeforeEach
    void stubTokenAsValid() {
        // Shared happy-path token decoding: a syntactically valid, unexpired refresh token whose
        // subject is USER_ID and whose rotation id is "jti-1". The session-store verdicts are what
        // differ per test.
        when(tokenProvider.getSubject(eq(REFRESH_TOKEN), any())).thenReturn(USER_ID);
        when(tokenProvider.isTokenValid(USER_ID, REFRESH_TOKEN)).thenReturn(true);
        when(tokenProvider.getTokenId(REFRESH_TOKEN)).thenReturn("jti-1");
    }

    @Test
    @DisplayName("replaying a superseded token revokes the whole family and refuses to rotate")
    void reuseDetectionRevokesFamilyAndDoesNotRotate() {
        RefreshSession superseded = RefreshSession.builder()
                .id(10L).userId(USER_ID).family("fam-1").jti("jti-1")
                .revoked(false).superseded(true) // already rotated once → this presentation is a replay
                .build();
        when(jdbcTemplate.query(eq(SELECT_SESSION_BY_JTI_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(superseded));
        UserDTO owner = new UserDTO();
        owner.setId(USER_ID);
        owner.setEmail("victim@example.com");
        when(userService.getUserById(USER_ID)).thenReturn(owner);

        assertThrows(ApiException.class, () -> sessionService.rotate(REFRESH_TOKEN, request));

        // The whole family is revoked (the reuse response)...
        verify(jdbcTemplate).update(eq(REVOKE_FAMILY_QUERY), anyMap());
        // ...and NO rotation happens: the presented token is not superseded again, and no new session
        // row is minted — a replayer is never handed a fresh token.
        verify(jdbcTemplate, never()).update(eq(SUPERSEDE_SESSION_QUERY), anyMap());
        verify(jdbcTemplate, never()).update(eq(INSERT_SESSION_QUERY), any(SqlParameterSource.class));
        // The incident is audited so the user sees it in their activity log.
        verify(eventPublisher).publishEvent(any(NewUserEvent.class));
    }

    @Test
    @DisplayName("an unknown jti (valid JWT, no matching session row) refuses with no writes")
    void unknownJtiRefusesWithoutWrites() {
        when(jdbcTemplate.query(eq(SELECT_SESSION_BY_JTI_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of()); // no session row for this jti

        assertThrows(ApiException.class, () -> sessionService.rotate(REFRESH_TOKEN, request));

        // No supersede, revoke, or any other named-map write occurs.
        verify(jdbcTemplate, never()).update(anyString(), anyMap());
        verify(jdbcTemplate, never()).update(anyString(), any(SqlParameterSource.class));
    }
}
