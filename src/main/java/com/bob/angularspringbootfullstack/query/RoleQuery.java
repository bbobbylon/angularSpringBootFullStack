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
     * Links a user to a role for authorization purposes. {@code expires_at} is left NULL
     * (unlimited) — this is only ever used for the unlimited {@code ROLE_USER} grant a new
     * registration receives; time-boxed assignments go through
     * {@link #UPDATE_USER_ROLE_QUERY} instead.
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
     * Selects a single role catalog row by its own id (not a user's assigned role — see
     * {@link #SELECT_ROLE_BY_ID_QUERY} for that). Backs the Role CRUD catalog operations.
     * Parameter: id (roles.id)
     */
    public static final String SELECT_ROLE_QUERY = "SELECT * FROM roles WHERE id = :id";

    /**
     * Selects the role assigned to a specific user, including the per-assignment
     * {@code expires_at} timestamp (time-boxed role assignment) so
     * {@code RoleRepoImpl#getRoleByUserId} can enforce it live on every lookup.
     * Performs a JOIN across users, user_roles, and roles tables to fetch
     * the complete role information for a user.
     * Parameter: id (user_id)
     */
    // Lowercase "users" — the actual table name per schema.sql. Windows/native MySQL is
    // case-insensitive and tolerated "Users" silently; Aiven (Linux-hosted, case-sensitive)
    // does not, so this broke real registration/login responses in production the first
    // time this code path ran against it.
    public static final String SELECT_ROLE_BY_ID_QUERY = "SELECT r.id, r.name, r.permission, ur.expires_at FROM roles r JOIN userroles ur ON ur.role_id = r.id JOIN users u ON u.id = ur.user_id WHERE u.id = :id";
    /**
     * Selects every role row ordered by ID.
     * <p>
     * Used by {@code GET /user/profile} to embed the full roles list in the profile
     * response, allowing the frontend Authorization tab to populate its role selector
     * without a separate request.
     */
    public static final String SELECT_ALL_ROLES_QUERY = "SELECT * FROM roles ORDER BY id";

    /**
     * Updates the role assigned to a user in the junction table, and its expiry alongside it.
     * Replaces the existing role assignment with the new role. {@code expiresAt} is bound as
     * SQL NULL for an unlimited assignment (the default) or a timestamp for a time-boxed one;
     * reassigning a role always overwrites whatever expiry the previous assignment carried.
     * Also how {@code RoleRepoImpl#getRoleByUserId} performs the live auto-revert to
     * {@code ROLE_USER} once a time-boxed assignment's expiry has passed.
     * Parameters: roleId, expiresAt (nullable), userId
     */
    public static final String UPDATE_USER_ROLE_QUERY = "UPDATE userroles SET role_id = :roleId, expires_at = :expiresAt WHERE user_id = :userId";

    /**
     * Inserts a new row into the role catalog (Role CRUD — create). Named parameters are bound
     * from a {@link com.bob.angularspringbootfullstack.model.Role}'s name and permission; the
     * generated id is recovered via a {@code GeneratedKeyHolder}, matching the convention every
     * other {@code *RepoImpl#create} in this codebase uses for an AUTO_INCREMENT primary key.
     * Parameters: name, permission
     */
    public static final String INSERT_ROLE_QUERY = "INSERT INTO roles (name, permission) VALUES (:name, :permission)";

    /**
     * Updates the permission string of an existing role catalog row (Role CRUD — edit). The
     * role's {@code name} is deliberately not updatable here: {@link
     * com.bob.angularspringbootfullstack.enumeration.RoleType} ties its compile-time tier
     * ladder to the exact role name, so renaming a row out from under it would silently strand
     * every user currently holding that role.
     * Parameters: permission, id
     */
    public static final String UPDATE_ROLE_PERMISSION_QUERY = "UPDATE roles SET permission = :permission WHERE id = :id";

    /**
     * Deletes a role catalog row (Role CRUD — delete). {@code userroles.role_id} carries
     * {@code ON DELETE RESTRICT}, so this throws a {@code DataIntegrityViolationException} —
     * translated by {@code RoleRepoImpl#delete} into a client-facing {@code ApiException} —
     * if any user currently holds the role being deleted.
     * Parameter: id
     */
    public static final String DELETE_ROLE_QUERY = "DELETE FROM roles WHERE id = :id";
}
