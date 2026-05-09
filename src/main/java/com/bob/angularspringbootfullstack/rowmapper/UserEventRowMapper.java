package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.UserEvent;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a JDBC {@link ResultSet} row to a {@link UserEvent}.
 *
 * <p>Used by {@link com.bob.angularspringbootfullstack.repo.repoimpl.EventRepoImpl}
 * when querying the joined {@code userevents ↔ events} result set.  Column
 * names here must exactly match the aliases produced by
 * {@link com.bob.angularspringbootfullstack.query.EventQuery#SELECT_EVENTS_BY_USER_ID_QUERY}
 * — a mismatch causes a runtime {@link SQLException}.
 *
 * <p>Note: the database column is {@code ip_address} (snake_case) but the Java
 * field is {@code ipAddress} (camelCase) — the mapping is explicit here rather
 * than relying on any naming convention.
 */
public class UserEventRowMapper implements RowMapper<UserEvent> {

    /**
     * Builds a {@link UserEvent} from the current row of the result set.
     *
     * @param rs     the result set positioned on the current row
     * @param rowNum the zero-based index of the current row (unused here)
     * @return a fully populated {@link UserEvent}
     * @throws SQLException if any expected column is missing or cannot be read
     */
    @Override
    public UserEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return UserEvent.builder()
                .id(rs.getLong("id"))
                .type(rs.getString("type"))
                .description(rs.getString("description"))
                .device(rs.getString("device"))
                .ipAddress(rs.getString("ip_address"))
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                .build();
    }
}
