package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Role;

import java.util.Collection;

/**
 * Service-layer facade for role lookups.
 *
 * <p>In this project roles store the permission string used to build Spring Security authorities.
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
     * comma-delimited permissions string used to build Spring Security authorities.
     *
     * @return a collection of all available {@link Role} entities
     */
}
     * Retrieves a role by its name.
     *
     * @param name The name of the role to retrieve.
     * @return The Role object if found, otherwise null.
     */
    Role getRoleByRoleName(String name);

    /**
     * Retrieves the permissions for a specific user.
     *
     * @param id The ID of the user.
     * @return A collection of roles, which contain permissions.
     */
    Collection<Role> getRolesByUserId(Long id);
}
