package com.bob.angularspringbootfullstack.query;

/**
 * RoleQuery contains all SQL query constants for role-related database operations.
 *
 * These queries use named parameters (`:paramName`) instead of positional parameters (`?`)
 * to work with Spring's NamedParameterJdbcTemplate. Named parameters are set in the
 * MapSqlParameterSource using .addValue() method calls.
 *
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
    public static final String SELECT_ROLE_BY_ID_QUERY = "SELECT r.id, r.name, r.permission FROM roles r JOIN userroles ur ON ur.role_id = r.id JOIN Users u ON u.id = ur.user_id WHERE u.id = :id";
}
