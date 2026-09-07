package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.utils.AuditHashChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.bob.angularspringbootfullstack.query.EventQuery.INSERT_EVENT_WITH_DETAIL_BY_EMAIL_QUERY;
import static com.bob.angularspringbootfullstack.query.EventQuery.SELECT_LATEST_USEREVENT_HASH_QUERY;
import static com.bob.angularspringbootfullstack.query.EventQuery.SELECT_USER_ID_BY_EMAIL_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code userevents} tamper-evidence hash chain
 * (FUTURE-ENHANCEMENTS §3.1) written by {@link EventRepoImpl#addUserEvent(String, EventType, String, String, String)}.
 * {@link NamedParameterJdbcTemplate} is mocked, so these tests exercise the chaining logic itself,
 * not SQL correctness — the same split {@code SecuritySettingsServiceImplTest} draws.
 *
 * <p>{@code createdAt} is generated inside the method under test ({@code LocalDateTime.now()}), so
 * each test recomputes its expected hash from the exact {@code createdAt} the method actually bound,
 * captured off the insert parameters, rather than asserting against a hardcoded digest.
 */
@ExtendWith(MockitoExtension.class)
class EventRepoImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private EventRepoImpl repo;

    @Test
    @DisplayName("the first row ever written starts a new chain (null previous hash)")
    void firstRowStartsNewChain() {
        when(jdbcTemplate.queryForObject(eq(SELECT_USER_ID_BY_EMAIL_QUERY), anyMap(), eq(Long.class))).thenReturn(7L);
        when(jdbcTemplate.query(eq(SELECT_LATEST_USEREVENT_HASH_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);

        repo.addUserEvent("user@example.com", EventType.LOGIN_ATTEMPT_SUCCESS, "chrome", "1.2.3.4", "detail");

        verify(jdbcTemplate).update(eq(INSERT_EVENT_WITH_DETAIL_BY_EMAIL_QUERY), captor.capture());
        SqlParameterSource params = captor.getValue();
        LocalDateTime createdAt = (LocalDateTime) params.getValue("createdAt");
        String expectedHash = AuditHashChain.computeHash(null, 7L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", "detail", createdAt);
        assertThat(params.getValue("hash")).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("a subsequent row chains from the previous row's stored hash")
    void subsequentRowChainsFromPreviousHash() {
        String previousHash = "a".repeat(64);
        when(jdbcTemplate.queryForObject(eq(SELECT_USER_ID_BY_EMAIL_QUERY), anyMap(), eq(Long.class))).thenReturn(7L);
        when(jdbcTemplate.query(eq(SELECT_LATEST_USEREVENT_HASH_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(previousHash));
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);

        repo.addUserEvent("user@example.com", EventType.LOGIN_ATTEMPT_SUCCESS, "chrome", "1.2.3.4", "detail");

        verify(jdbcTemplate).update(eq(INSERT_EVENT_WITH_DETAIL_BY_EMAIL_QUERY), captor.capture());
        SqlParameterSource params = captor.getValue();
        LocalDateTime createdAt = (LocalDateTime) params.getValue("createdAt");
        String expectedHash = AuditHashChain.computeHash(previousHash, 7L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", "detail", createdAt);
        assertThat(params.getValue("hash")).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("a legacy most-recent row with a null stored hash also starts a new chain")
    void legacyPreviousRowAlsoStartsNewChain() {
        when(jdbcTemplate.queryForObject(eq(SELECT_USER_ID_BY_EMAIL_QUERY), anyMap(), eq(Long.class))).thenReturn(7L);
        when(jdbcTemplate.query(eq(SELECT_LATEST_USEREVENT_HASH_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(Arrays.asList((String) null));
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);

        repo.addUserEvent("user@example.com", EventType.LOGIN_ATTEMPT_SUCCESS, "chrome", "1.2.3.4", "detail");

        verify(jdbcTemplate).update(eq(INSERT_EVENT_WITH_DETAIL_BY_EMAIL_QUERY), captor.capture());
        SqlParameterSource params = captor.getValue();
        LocalDateTime createdAt = (LocalDateTime) params.getValue("createdAt");
        String expectedHash = AuditHashChain.computeHash(null, 7L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", "detail", createdAt);
        assertThat(params.getValue("hash")).isEqualTo(expectedHash);
    }
}
