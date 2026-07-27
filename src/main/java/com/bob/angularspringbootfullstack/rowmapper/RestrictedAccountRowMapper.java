package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.RestrictedAccount;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maps one row of
 * {@link com.bob.angularspringbootfullstack.query.SecurityDashboardQuery#SELECT_RESTRICTED_ACCOUNTS_QUERY}
 * onto a {@link RestrictedAccount} (SRS FR-TPF-2).
 *
 * <p>{@code last_failure_at} comes from a correlated subquery and is genuinely nullable — an
 * account disabled administratively, or one that was never verified, has no failed sign-in behind
 * it. That null is preserved rather than defaulted to an epoch date, because "never failed" and
 * "failed in 1970" would sort together while meaning opposite things.
 */
public class RestrictedAccountRowMapper implements RowMapper<RestrictedAccount> {

    @Override
    public RestrictedAccount mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp lastFailureAt = resultSet.getTimestamp("last_failure_at");
        return new RestrictedAccount(
                resultSet.getLong("user_id"),
                resultSet.getString("first_name"),
                resultSet.getString("last_name"),
                resultSet.getString("email"),
                resultSet.getBoolean("non_locked"),
                resultSet.getBoolean("enabled"),
                lastFailureAt == null ? null : lastFailureAt.toLocalDateTime());
    }
}
