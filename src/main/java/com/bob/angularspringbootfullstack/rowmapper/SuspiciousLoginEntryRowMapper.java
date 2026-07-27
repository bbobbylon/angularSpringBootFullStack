package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.SuspiciousLoginEntry;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maps one row of
 * {@link com.bob.angularspringbootfullstack.query.SecurityDashboardQuery#SELECT_RECENT_SUSPICIOUS_LOGINS_QUERY}
 * onto a {@link SuspiciousLoginEntry} (SRS FR-TPF-2).
 *
 * <p>Like {@link LoginContextRowMapper}, this builds a {@code record} rather than a Lombok model —
 * the target is a read-only projection assembled for one screen, not a persisted entity.
 *
 * <p>{@code created_at} is read through {@link Timestamp} and converted explicitly rather than via
 * {@code getObject(..., LocalDateTime.class)}. The MySQL driver's direct {@code LocalDateTime}
 * support depends on connector version and on whether the column is {@code DATETIME} or
 * {@code TIMESTAMP}; going through {@code Timestamp} behaves identically everywhere and is what the
 * rest of this package already does. The null guard matters because the query's {@code LEFT}-style
 * projections can carry SQL NULLs, and {@link Timestamp#toLocalDateTime()} would throw on one.
 */
public class SuspiciousLoginEntryRowMapper implements RowMapper<SuspiciousLoginEntry> {

    @Override
    public SuspiciousLoginEntry mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new SuspiciousLoginEntry(
                resultSet.getLong("user_id"),
                resultSet.getString("first_name"),
                resultSet.getString("last_name"),
                resultSet.getString("email"),
                resultSet.getString("device"),
                resultSet.getString("ip_address"),
                resultSet.getString("detail"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }
}
