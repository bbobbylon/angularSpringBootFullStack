package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.AuditChainVerificationResult;
import com.bob.angularspringbootfullstack.service.serviceimpl.AuditIntegrityServiceImpl.ChainedRow;
import com.bob.angularspringbootfullstack.utils.AuditHashChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static com.bob.angularspringbootfullstack.query.EventQuery.SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ALL_ORGANIZATIONEVENTS_FOR_VERIFICATION_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditIntegrityServiceImpl}'s hash-chain re-verification
 * (FUTURE-ENHANCEMENTS §3.1). {@link NamedParameterJdbcTemplate} is mocked to return
 * pre-built {@link ChainedRow}s directly — see that record's Javadoc for why this is preferred
 * over mocking a JDBC {@code ResultSet} — so what is actually under test is
 * {@code AuditIntegrityServiceImpl#verifyChain}'s walk-and-compare logic, independent of SQL.
 */
@ExtendWith(MockitoExtension.class)
class AuditIntegrityServiceImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private AuditIntegrityServiceImpl service;

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 1, 10, 5, 0);

    @Test
    @DisplayName("an intact two-row chain reports intact with both rows checked")
    void intactChainReportsIntact() {
        Object[] row1Fields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T1};
        String hash1 = AuditHashChain.computeHash(null, row1Fields);
        Object[] row2Fields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T2};
        String hash2 = AuditHashChain.computeHash(hash1, row2Fields);

        when(jdbcTemplate.query(eq(SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of(new ChainedRow(1L, hash1, row1Fields), new ChainedRow(2L, hash2, row2Fields)));

        AuditChainVerificationResult result = service.verifyUserEvents();

        assertThat(result.intact()).isTrue();
        assertThat(result.rowsChecked()).isEqualTo(2);
        assertThat(result.firstBrokenId()).isNull();
    }

    @Test
    @DisplayName("a row whose stored hash was altered is reported as the first break")
    void tamperedRowIsReportedAsBroken() {
        Object[] row1Fields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T1};
        String hash1 = AuditHashChain.computeHash(null, row1Fields);
        Object[] row2Fields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T2};
        String tamperedHash2 = "0".repeat(64);

        when(jdbcTemplate.query(eq(SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of(new ChainedRow(1L, hash1, row1Fields), new ChainedRow(2L, tamperedHash2, row2Fields)));

        AuditChainVerificationResult result = service.verifyUserEvents();

        assertThat(result.intact()).isFalse();
        assertThat(result.rowsChecked()).isEqualTo(2);
        assertThat(result.firstBrokenId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("legacy rows with a null stored hash are skipped and reported as zero checked")
    void legacyRowsAreSkippedNotCountedAsFailures() {
        Object[] legacyFields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T1};

        when(jdbcTemplate.query(eq(SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of(new ChainedRow(1L, null, legacyFields), new ChainedRow(2L, null, legacyFields)));

        AuditChainVerificationResult result = service.verifyUserEvents();

        assertThat(result.intact()).isTrue();
        assertThat(result.rowsChecked()).isZero();
    }

    @Test
    @DisplayName("a chain resumes correctly on the first row written after a run of legacy rows")
    void chainResumesAfterLegacyRows() {
        Object[] legacyFields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T1};
        Object[] firstChainedFields = {1L, "LOGIN_ATTEMPT_SUCCESS", "chrome", "1.2.3.4", null, T2};
        String hash = AuditHashChain.computeHash(null, firstChainedFields);

        when(jdbcTemplate.query(eq(SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of(new ChainedRow(1L, null, legacyFields), new ChainedRow(2L, hash, firstChainedFields)));

        AuditChainVerificationResult result = service.verifyUserEvents();

        assertThat(result.intact()).isTrue();
        assertThat(result.rowsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty organizationevents table reports intact with zero rows checked")
    void emptyOrganizationEventsTableIsIntact() {
        when(jdbcTemplate.query(eq(SELECT_ALL_ORGANIZATIONEVENTS_FOR_VERIFICATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of());

        AuditChainVerificationResult result = service.verifyOrganizationEvents();

        assertThat(result.intact()).isTrue();
        assertThat(result.rowsChecked()).isZero();
        assertThat(result.firstBrokenId()).isNull();
    }
}
