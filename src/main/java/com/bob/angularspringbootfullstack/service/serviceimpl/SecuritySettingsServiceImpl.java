package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.SecuritySettings;
import com.bob.angularspringbootfullstack.rowmapper.SecuritySettingsRowMapper;
import com.bob.angularspringbootfullstack.service.SecuritySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.SecuritySettingsQuery.SELECT_SECURITY_SETTINGS_QUERY;
import static com.bob.angularspringbootfullstack.query.SecuritySettingsQuery.UPDATE_SECURITY_SETTINGS_QUERY;

/**
 * JDBC-backed implementation of {@link SecuritySettingsService}, following the same
 * service-owns-its-SQL shape as {@link com.bob.angularspringbootfullstack.service.serviceimpl.OrganizationServiceImpl}
 * — this table has exactly one row,
 * so a dedicated {@code Repo}/{@code RepoImpl} pair would add a layer with nothing to abstract.
 *
 * <p>Reads and writes go straight to the database on every call, deliberately uncached. The whole
 * point of this table is that {@code LoginRiskServiceImpl#assess} — which runs on every login —
 * sees an admin's change immediately; a cache would reintroduce the "only takes effect after a
 * restart" problem this feature exists to remove, in exchange for saving a single-row primary-key
 * lookup that MySQL answers from its buffer pool in practice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecuritySettingsServiceImpl implements SecuritySettingsService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     *
     * <p>Falls back to an all-{@code null} (no overrides) settings object rather than throwing if
     * the pinned row is somehow missing — a database mid-migration, or a test schema that never ran
     * the full {@code schema.sql} — so a missing settings row degrades to "behave exactly like
     * before this feature existed" instead of breaking every login.
     */
    @Override
    public SecuritySettings getSettings() {
        List<SecuritySettings> rows = jdbcTemplate.query(SELECT_SECURITY_SETTINGS_QUERY, new SecuritySettingsRowMapper());
        if (rows.isEmpty()) {
            log.warn("securitysettings row (id=1) is missing; anomaly detection is falling back to env defaults");
            return SecuritySettings.builder().build();
        }
        return rows.get(0);
    }

    @Override
    public SecuritySettings updateSettings(Boolean anomalyEnabled, Integer anomalyHistoryLimit, Long updatedBy) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("anomalyEnabled", anomalyEnabled)
                .addValue("anomalyHistoryLimit", anomalyHistoryLimit)
                .addValue("updatedBy", updatedBy);
        jdbcTemplate.update(UPDATE_SECURITY_SETTINGS_QUERY, params);
        log.info("Security settings updated by admin id {}: anomalyEnabled={}, anomalyHistoryLimit={}",
                updatedBy, anomalyEnabled, anomalyHistoryLimit);
        return getSettings();
    }
}
