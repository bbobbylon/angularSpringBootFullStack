package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.OrgSsoDomain;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * OrgSsoDomainRowMapper converts {@code organizationssodomains} rows into {@link OrgSsoDomain}
 * objects for {@code OrganizationIdentityProviderServiceImpl}.
 */
public class OrgSsoDomainRowMapper implements RowMapper<OrgSsoDomain> {
    @Override
    public OrgSsoDomain mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return OrgSsoDomain.builder()
                .id(resultSet.getLong("id"))
                .organizationId(resultSet.getLong("organization_id"))
                .domain(resultSet.getString("domain"))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .build();
    }
}
