package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.User;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * UserRowMapper converts database ResultSet rows into User objects.
 * <p>
 * This mapper implements Spring's RowMapper interface and is used by
 * NamedParameterJdbcTemplate to automatically convert query result rows
 * into strongly typed User objects. This replaces manual ResultSet parsing.
 * <p>
 * How it works:
 * 1. Spring JDBC calls mapRow() for each row in the ResultSet
 * 2. We extract each column and map it to the corresponding User field
 * 3. We use Lombok's Builder pattern (via @SuperBuilder) to create the User
 * 4. The fully constructed User object is returned
 */
public class UserRowMapper implements RowMapper<User> {
    /**
     * Maps a single database row to a User object.
     * <p>
     * This method is called by Spring JDBC for each row in the query result.
     * It extracts values from the ResultSet and builds a User object using
     * the builder pattern provided by Lombok's @SuperBuilder annotation.
     * <p>
     * Column mappings:
     * - Database: Java field
     * - Id → id
     * - First_name → firstName
     * - Last_name → lastName
     * - Email → email
     * - Password → password
     * - Enabled → enabled
     * - Title → title
     * - Bio → bio
     * - Non_locked → isNotLocked
     * - Created_at → createdAt
     * - Using_mfa → isUsing2FA
     * - Phone → phoneNumber
     * - Image_url → imageUrl
     * - Address → address
     *
     * @param resultSet the SQL result set positioned at the current row
     * @param rowNum    the row number (0-indexed)
     * @return a fully initialized User object
     * @throws SQLException if a database access error occurs
     */
    @Override
    public User mapRow(ResultSet resultSet, int rowNum) throws SQLException {
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
                .usingTotp(resultSet.getBoolean("using_totp"))
                .usingPasskey(resultSet.getBoolean("using_passkey"))
                .phoneNumber(resultSet.getString("phone"))
                .imageUrl(resultSet.getString("image_url"))
                .address(resultSet.getString("address"))
                .passwordChangedAt(resultSet.getTimestamp("password_changed_at") != null
                        ? resultSet.getTimestamp("password_changed_at").toLocalDateTime()
                        : null)
                .origin(resultSet.getString("origin"))
                .build();

    }
}
