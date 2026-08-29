package com.bob.angularspringbootfullstack.service.serviceimpl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.query.ProviderLinkTicketQuery.DELETE_EXPIRED_TICKETS_QUERY;
import static com.bob.angularspringbootfullstack.query.ProviderLinkTicketQuery.DELETE_TICKET_QUERY;
import static com.bob.angularspringbootfullstack.query.ProviderLinkTicketQuery.INSERT_TICKET_QUERY;
import static com.bob.angularspringbootfullstack.query.ProviderLinkTicketQuery.SELECT_TICKET_QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DB-backed federated account-link ticket store (FUTURE-ENHANCEMENTS §2.4).
 * {@link NamedParameterJdbcTemplate} is mocked so no database is involved — these tests exist to
 * pin down the redemption verdicts (unknown, expired, provider-mismatch, concurrently-consumed,
 * happy path), the same shape {@code SessionServiceImplTest} uses for the sibling JDBC-backed
 * {@code SessionServiceImpl}.
 *
 * <p>The reflective row-construction helper below stands in for a real {@code providerlinktickets}
 * row: because {@code ProviderLinkTicketService}'s row-mapper record is private, a test cannot
 * build one directly, so each test instead stubs the mocked {@code jdbcTemplate.query(...)} call
 * to invoke the real {@link RowMapper} lambda against a mocked {@code ResultSet} — exercising the
 * production mapping code path rather than bypassing it.
 */
@ExtendWith(MockitoExtension.class)
class ProviderLinkTicketServiceTest {

    private static final String TICKET = "11111111-1111-1111-1111-111111111111";
    private static final Long USER_ID = 7L;
    private static final String PROVIDER = "google";

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private ProviderLinkTicketService ticketService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ticketService = new ProviderLinkTicketService(jdbcTemplate);
    }

    /** Stubs the SELECT to return one row shaped by mapping the given values through the real RowMapper. */
    @SuppressWarnings("unchecked")
    private void stubSelectReturns(Long userId, String provider, LocalDateTime expiresAt) throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(rs.getLong("user_id")).thenReturn(userId);
        when(rs.getString("provider")).thenReturn(provider);
        when(rs.getTimestamp("expires_at")).thenReturn(java.sql.Timestamp.valueOf(expiresAt));

        when(jdbcTemplate.query(eq(SELECT_TICKET_QUERY), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @SuppressWarnings("unchecked")
    private void stubSelectReturnsNothing() {
        when(jdbcTemplate.query(eq(SELECT_TICKET_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("mint purges expired rows first, then inserts a fresh ticket bound to the caller")
    void mintPurgesThenInserts() {
        String ticket = ticketService.mint(USER_ID, PROVIDER);

        assertTrue(ticket != null && !ticket.isBlank());
        verify(jdbcTemplate).update(eq(DELETE_EXPIRED_TICKETS_QUERY), anyMap());
        verify(jdbcTemplate).update(eq(INSERT_TICKET_QUERY), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("redeeming an unknown ticket returns empty without touching the delete statement")
    void redeemUnknownTicketReturnsEmpty() {
        stubSelectReturnsNothing();

        Optional<Long> result = ticketService.redeem(TICKET, PROVIDER);

        assertTrue(result.isEmpty());
        verify(jdbcTemplate, never()).update(eq(DELETE_TICKET_QUERY), anyMap());
    }

    @Test
    @DisplayName("redeeming a blank or null ticket short-circuits before any database call")
    void redeemBlankTicketShortCircuits() {
        assertTrue(ticketService.redeem(null, PROVIDER).isEmpty());
        assertTrue(ticketService.redeem("   ", PROVIDER).isEmpty());
        verify(jdbcTemplate, never()).query(eq(SELECT_TICKET_QUERY), anyMap(), any(RowMapper.class));
    }

    @Test
    @DisplayName("redeeming an expired ticket deletes the row (housekeeping) and returns empty")
    void redeemExpiredTicketDeletesAndReturnsEmpty() throws Exception {
        stubSelectReturns(USER_ID, PROVIDER, LocalDateTime.now().minusMinutes(1));

        Optional<Long> result = ticketService.redeem(TICKET, PROVIDER);

        assertTrue(result.isEmpty());
        verify(jdbcTemplate).update(eq(DELETE_TICKET_QUERY), anyMap());
    }

    @Test
    @DisplayName("a provider mismatch is refused WITHOUT consuming the ticket")
    void redeemProviderMismatchDoesNotConsume() throws Exception {
        stubSelectReturns(USER_ID, "github", LocalDateTime.now().plusMinutes(4));

        Optional<Long> result = ticketService.redeem(TICKET, "google");

        assertTrue(result.isEmpty());
        // Deliberately not deleted — see the class javadoc on why a mismatch must not consume.
        verify(jdbcTemplate, never()).update(eq(DELETE_TICKET_QUERY), anyMap());
    }

    @Test
    @DisplayName("a concurrently-consumed ticket (delete affects zero rows) is reported as empty")
    void redeemConcurrentlyConsumedTicketReturnsEmpty() throws Exception {
        stubSelectReturns(USER_ID, PROVIDER, LocalDateTime.now().plusMinutes(4));
        when(jdbcTemplate.update(eq(DELETE_TICKET_QUERY), anyMap())).thenReturn(0);

        Optional<Long> result = ticketService.redeem(TICKET, PROVIDER);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("a valid, matching, unexpired ticket redeems successfully exactly once")
    void redeemHappyPathReturnsUserId() throws Exception {
        stubSelectReturns(USER_ID, PROVIDER, LocalDateTime.now().plusMinutes(4));
        when(jdbcTemplate.update(eq(DELETE_TICKET_QUERY), anyMap())).thenReturn(1);

        Optional<Long> result = ticketService.redeem(TICKET, PROVIDER);

        assertEquals(Optional.of(USER_ID), result);
    }
}
