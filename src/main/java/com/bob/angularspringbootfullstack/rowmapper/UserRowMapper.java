package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.User;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class UserRowMapper implements RowMapper<User> {
    // we will map all the roles into a Java Object with this class
    @Override
    public User mapRow(ResultSet resultSet, int rowNum) throws SQLException {
// we are going to map everything from the result set to the User object, and then we will return the User object. We are using .builder() because the type of this class<User> User.java file has a SuperBuilder annotation which allows us to bypass the getters/setters and constructor setup. It will create a constructor, pass in the values via our setters below, and then set those values and return it back to us!
        return User.builder()
                .id(resultSet.getLong("id"))
                .firstName(resultSet.getString("first_name"))
                .lastName(resultSet.getString("last_name"))
                .email(resultSet.getString("email"))
                .password(resultSet.getString("password"))
                .enabled(resultSet.getBoolean("enabled"))
                .title(resultSet.getString("title"))
                .bio(resultSet.getString("bio"))
                .isNotLocked(resultSet.getBoolean("non_locked"))
                .createdAt(resultSet.getTimestamp("created_at").toLocalDateTime())
                .isUsing2FA(resultSet.getBoolean("using_mfa"))
                .phoneNumber(resultSet.getString("phone"))
                .imageUrl(resultSet.getString("image_url"))
                .address(resultSet.getString("address"))
                .build();

    }
}
