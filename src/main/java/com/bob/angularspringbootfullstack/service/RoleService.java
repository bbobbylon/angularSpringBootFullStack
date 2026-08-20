package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Role;

import java.util.Collection;

/**
 * Service-layer facade for role lookups and the role catalog (Role CRUD).
 *
 * <p>In this project roles store the permission string used to build Spring Security authorities.
 * Catalog mutations (create/edit-permission/delete) are exposed here rather than only on the
 * repository so that business rules that are not this application's data-access concern —
 * name-format validation and refusing to delete a built-in
 * {@link com.bob.angularspringbootfullstack.enumeration.RoleType} role — have a home, per this
 * codebase's convention of keeping business logic in the service layer, not the repo.
 */
public interface RoleService {
    /**
     * Returns the role assigned to a user.
     *
     * @param id user id
     * @return the user's role
     */
    Role getRoleByUserId(Long id);

    /**
     * Returns all roles defined in the system.
     *
     * <p>Included in the {@code GET /user/profile} response so the frontend can
     * populate the role selector in the Authorization tab without a separate
     * network request. Each {@link Role} carries both the display name and the
     * comma-delimited permissions string used to build Spring Security authorities,
     * plus {@link Role#isAssignable()} so the frontend can flag a catalog-only role
     * that has no {@link com.bob.angularspringbootfullstack.enumeration.RoleType}
     * constant yet as "created, not yet assignable".
     *
     * @return a collection of all available {@link Role} entities
     */
    Collection<Role> getAllRoles();

    /**
     * Creates a new role catalog row (Role CRUD — create), gated to
     * {@code ROLE_APPLICATION_ADMIN} at the controller.
     *
     * @param role the role to create; {@code name} must look like {@code ROLE_SOMETHING}
     *             (uppercase letters/underscores) and {@code permission} must be non-blank
     * @return the created role with its generated id populated
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if the name is malformed,
     *         blank, or already taken
     */
    Role createRole(Role role);

    /**
     * Updates an existing role's permission string (Role CRUD — edit). The name is immutable;
     * see {@code RoleQuery#UPDATE_ROLE_PERMISSION_QUERY}'s Javadoc for why.
     *
     * @param id         the id of the role to update
     * @param permission the new comma-delimited permission string
     * @return the updated role
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if the permission string
     *         is blank, or no role has that id
     */
    Role updateRolePermission(Long id, String permission);

    /**
     * Deletes a role from the catalog (Role CRUD — delete).
     *
     * <p>Refuses to delete any of the seven built-in roles that have a
     * {@link com.bob.angularspringbootfullstack.enumeration.RoleType} constant — deleting one
     * out from under the compile-time tier ladder would strand anyone still holding it and
     * silently break {@link com.bob.angularspringbootfullstack.enumeration.RoleType#canAssign}
     * for that name. A catalog-only role created through {@link #createRole} has no such
     * constant and may always be deleted (subject to the database's own
     * {@code ON DELETE RESTRICT} guard against a role still assigned to a user).
     *
     * @param id the id of the role to delete
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no role has that id,
     *         if it is a built-in role, or if any user currently holds it
     */
    void deleteRole(Long id);
}
