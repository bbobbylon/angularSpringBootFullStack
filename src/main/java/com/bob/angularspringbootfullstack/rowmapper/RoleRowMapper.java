package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.Role;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class RoleRowMapper implements RowMapper<Role> {
    // we will map all the roles into a Java Object with this class
    @Override
    public Role mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        // we are using .builder() because the type of this class<Role> Role.java file has a SuperBuilder annotation which allows us to bypass the getters/setters and constructor setup. It will create a constructor, pass in the values via our setters below, and then set those values and return it back to us
        return Role.builder()
                .id(resultSet.getLong("id"))
                .name(resultSet.getString("name"))
                .permission(resultSet.getString("permission"))
                .build();
    }
}
