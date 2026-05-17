package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.Role;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * RoleRowMapper converts database ResultSet rows into Role objects.
 * <p>
 * This mapper implements Spring's RowMapper interface and is used by
 * NamedParameterJdbcTemplate to automatically convert query result rows
 * into strongly typed Role objects.
 * <p>
 * How it works:
 * 1. Spring JDBC calls mapRow() for each row in the ResultSet
 * 2. We extract each column and map it to the corresponding Role field
 * 3. We use Lombok's Builder pattern to create the Role
 * 4. The fully constructed Role object is returned
 */
public class RoleRowMapper implements RowMapper<Role> {
    /**
     * Maps a single database row to a Role object.
     * <p>
     * This method is called by Spring JDBC for each row in the query result.
     * It extracts values from the ResultSet and builds a Role object using
     * the builder pattern provided by Lombok's @SuperBuilder annotation.
     * <p>
     * Column mappings:
     * - Database: Java field
     * - Id → id
     * - Name → name
     * - Permission → permission
     *
     * @param resultSet the SQL result set positioned at the current row
     * @param rowNum    the row number (0-indexed)
     * @return a fully initialized Role object
     * @throws SQLException if a database access error occurs
     */
    @Override
    public Role mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return Role.builder()
                .id(resultSet.getLong("id"))
                .name(resultSet.getString("name"))
                .permission(resultSet.getString("permission"))
                .build();
    }
}
