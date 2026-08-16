package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.SecuritySettings;
import com.bob.angularspringbootfullstack.rowmapper.SecuritySettingsRowMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.SecuritySettingsQuery.SELECT_SECURITY_SETTINGS_QUERY;
import static com.bob.angularspringbootfullstack.query.SecuritySettingsQuery.UPDATE_SECURITY_SETTINGS_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin-tunable anomaly settings store (FUTURE-ENHANCEMENTS "Anomaly signal
 * tuning UI"). {@link NamedParameterJdbcTemplate} is mocked, so no database is involved — the
 * value under test is the null-vs-value contract this class exists to keep honest, not SQL
 * correctness.
 */
@ExtendWith(MockitoExtension.class)
class SecuritySettingsServiceImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private SecuritySettingsServiceImpl service;

    @Test
    @DisplayName("a missing settings row degrades to all-null overrides instead of throwing")
    void missingRowDegradesToNoOverrides() {
        when(jdbcTemplate.query(eq(SELECT_SECURITY_SETTINGS_QUERY), any(SecuritySettingsRowMapper.class)))
                .thenReturn(List.of());

        SecuritySettings settings = service.getSettings();

        assertThat(settings.getAnomalyEnabled()).isNull();
        assertThat(settings.getAnomalyHistoryLimit()).isNull();
    }

    @Test
    @DisplayName("getSettings() returns the persisted row as-is, including a null override")
    void returnsThePersistedRow() {
        SecuritySettings row = SecuritySettings.builder().id(1L).anomalyEnabled(false).anomalyHistoryLimit(null).build();
        when(jdbcTemplate.query(eq(SELECT_SECURITY_SETTINGS_QUERY), any(SecuritySettingsRowMapper.class)))
                .thenReturn(List.of(row));

        SecuritySettings settings = service.getSettings();

        assertThat(settings.getAnomalyEnabled()).isFalse();
        assertThat(settings.getAnomalyHistoryLimit()).isNull();
    }

    @Test
    @DisplayName("updateSettings() writes both fields as given, including a null that clears an override")
    void updateWritesBothFieldsIncludingNulls() {
        when(jdbcTemplate.query(eq(SELECT_SECURITY_SETTINGS_QUERY), any(SecuritySettingsRowMapper.class)))
                .thenReturn(List.of(SecuritySettings.builder().id(1L).anomalyEnabled(true).build()));
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);

        service.updateSettings(true, null, 42L);

        verify(jdbcTemplate).update(eq(UPDATE_SECURITY_SETTINGS_QUERY), captor.capture());
        SqlParameterSource params = captor.getValue();
        assertThat(params.getValue("anomalyEnabled")).isEqualTo(true);
        assertThat(params.getValue("anomalyHistoryLimit")).isNull();
        assertThat(params.getValue("updatedBy")).isEqualTo(42L);
    }
}
