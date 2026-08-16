package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.SecuritySettings;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maps one row of {@link com.bob.angularspringbootfullstack.query.SecuritySettingsQuery#SELECT_SECURITY_SETTINGS_QUERY}
 * onto a {@link SecuritySettings}.
 *
 * <p>{@code anomaly_enabled} and {@code anomaly_history_limit} use {@link ResultSet#getObject(String, Class)}
 * rather than {@code getBoolean}/{@code getInt}, which is deliberate: those two return {@code false}
 * and {@code 0} for a SQL {@code NULL} instead of a Java {@code null}, silently turning "no override
 * on record" into "override to disabled" / "override to zero" — exactly the two values this table
 * exists to distinguish from "unset".
 */
public class SecuritySettingsRowMapper implements RowMapper<SecuritySettings> {

    @Override
    public SecuritySettings mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return SecuritySettings.builder()
                .id(resultSet.getLong("id"))
                .anomalyEnabled(resultSet.getObject("anomaly_enabled", Boolean.class))
                .anomalyHistoryLimit(resultSet.getObject("anomaly_history_limit", Integer.class))
                .updatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime())
                .updatedBy(resultSet.getObject("updated_by", Long.class))
                .build();
    }
}
