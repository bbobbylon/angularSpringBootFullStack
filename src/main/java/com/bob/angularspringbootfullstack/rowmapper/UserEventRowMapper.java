package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.UserEvent;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class UserEventRowMapper implements RowMapper<UserEvent> {
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
