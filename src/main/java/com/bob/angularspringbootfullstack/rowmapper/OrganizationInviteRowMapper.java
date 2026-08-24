package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.OrganizationInvite;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a JDBC {@link ResultSet} row (joined {@code organizationinvites} ↔ {@code users}) to an
 * {@link OrganizationInvite}. Column names must exactly match the aliases produced by
 * {@code OrganizationQuery}'s invite-select constants.
 */
public class OrganizationInviteRowMapper implements RowMapper<OrganizationInvite> {
    @Override
    public OrganizationInvite mapRow(ResultSet rs, int rowNum) throws SQLException {
        return OrganizationInvite.builder()
                .id(rs.getLong("id"))
                .organizationId(rs.getLong("organization_id"))
                .invitedByUserId(rs.getLong("invited_by_user_id"))
                .invitedByEmail(rs.getString("invited_by_email"))
                .code(rs.getString("code"))
                .roleName(rs.getString("role_name"))
                .expirationDate(rs.getTimestamp("expiration_date").toLocalDateTime())
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                .build();
    }
}
