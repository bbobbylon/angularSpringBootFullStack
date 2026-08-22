package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.Organization;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * OrganizationRowMapper converts {@code organizations} table rows into {@link Organization}
 * objects, for {@code OrganizationRepoImpl}-style reads within {@code OrganizationServiceImpl}
 * (Organization CRUD — FUTURE-ENHANCEMENTS.md §3.2).
 * <p>
 * Mirrors {@link RoleRowMapper}'s shape: Spring JDBC calls {@link #mapRow} once per result row,
 * and Lombok's {@code @SuperBuilder} assembles the strongly typed object.
 */
public class OrganizationRowMapper implements RowMapper<Organization> {
    @Override
    public Organization mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return Organization.builder()
                .id(resultSet.getLong("id"))
                .name(resultSet.getString("name"))
                .status(resultSet.getString("status"))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .build();
    }
}
