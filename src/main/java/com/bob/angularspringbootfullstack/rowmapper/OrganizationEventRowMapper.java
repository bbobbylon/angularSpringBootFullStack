package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.OrganizationEvent;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a JDBC {@link ResultSet} row to an {@link OrganizationEvent} — the organization-scoped
 * counterpart to {@link UserEventRowMapper}. Column names here must exactly match the aliases
 * produced by {@code OrganizationQuery#SELECT_ORGANIZATION_EVENTS_PAGINATED_QUERY}.
 */
public class OrganizationEventRowMapper implements RowMapper<OrganizationEvent> {
    @Override
    public OrganizationEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return OrganizationEvent.builder()
                .id(rs.getLong("id"))
                .type(rs.getString("type"))
                .description(rs.getString("description"))
                .actorEmail(rs.getString("actor_email"))
                .detail(rs.getString("detail"))
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                .build();
    }
}
