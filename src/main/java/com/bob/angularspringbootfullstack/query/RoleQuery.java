package com.bob.angularspringbootfullstack.query;

/**
 * RoleQuery contains all SQL query constants for role-related database operations.
 * <p>
 * These queries use named parameters (`: paramName`) instead of positional parameters (`?`)
 * to work with Spring's NamedParameterJdbcTemplate. Named parameters are set in the
 * MapSqlParameterSource using .addValue() method calls.
 * <p>
 * Role queries handle both direct role lookups and user-role relationship operations.
 */
public class RoleQuery {
    /**
     * Inserts a user-role relationship into the user_roles junction table.
     * Links a user to a role for authorization purposes.
     * Parameters: userId, roleId
     */
    public static final String INSERT_ROLE_TO_USER_QUERY = "INSERT INTO userroles (user_id, role_id) VALUES (:userId, :roleId)";

    /**
     * Selects a role by its name.
     * Used to find role IDs for role assignment operations.
     * Parameter: name (e.g., "ROLE_USER", "ROLE_ADMIN")
     */
    public static final String SELECT_ROLE_BY_NAME_QUERY = "SELECT * FROM roles WHERE name = :name";

    /**
     * Selects the role assigned to a specific user.
     * Performs a JOIN across users, user_roles, and roles tables to fetch
     * the complete role information for a user.
     * Parameter: id (user_id)
     */
    // Note: table name is lowercase `users` to match schema.sql. Windows MySQL is case-insensitive
    // by default (lower_case_table_names=1), so `Users` worked there — but the Dockerized MySQL
    // runs on Linux with lower_case_table_names=0 (case-sensitive), where `Users` errors out as
    // "bad SQL grammar." Keeping all SQL identifiers lowercase makes the app portable across both.
    public static final String SELECT_ROLE_BY_ID_QUERY = "SELECT r.id, r.name, r.permission FROM roles r JOIN userroles ur ON ur.role_id = r.id JOIN users u ON u.id = ur.user_id WHERE u.id = :id";
    /**
     * Selects every role row ordered by ID.
     * <p>
     * Used by {@code GET /user/profile} to embed the full roles list in the profile
     * response, allowing the frontend Authorization tab to populate its role selector
     * without a separate request.
     */
    public static final String SELECT_ALL_ROLES_QUERY = "SELECT * FROM roles ORDER BY id";

    /**
     * Updates the role assigned to a user in the junction table.
     * Replaces the existing role assignment with the new role.
     * Parameters: roleId, userId
     */
    public static final String UPDATE_USER_ROLE_QUERY = "UPDATE userroles SET role_id = :roleId WHERE user_id = :userId";
}
