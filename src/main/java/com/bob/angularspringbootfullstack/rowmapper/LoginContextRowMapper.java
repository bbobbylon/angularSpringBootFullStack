package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.LoginContext;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps one row of
 * {@link com.bob.angularspringbootfullstack.query.LoginRiskQuery#SELECT_RECENT_LOGIN_CONTEXTS_BY_USER_ID_QUERY}
 * onto a {@link LoginContext}.
 *
 * <p>Unlike the other mappers in this package (which build Lombok-built model objects), this one
 * constructs a {@code record} directly — {@code LoginContext} is an immutable read-only projection,
 * not a persisted entity. The {@code last_seen} column the query selects for ordering is
 * intentionally not mapped: it exists only to sort the history, and the risk evaluation cares
 * about set membership, not recency.
 *
 * <p>{@code getString} returns {@code null} for a SQL NULL, which the caller
 * ({@link com.bob.angularspringbootfullstack.service.serviceimpl.LoginRiskServiceImpl}) treats as
 * "no usable fingerprint" rather than as a distinct device or network.
 */
public class LoginContextRowMapper implements RowMapper<LoginContext> {

    @Override
    public LoginContext mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new LoginContext(
                resultSet.getString("device"),
                resultSet.getString("ip_address"));
    }
}
