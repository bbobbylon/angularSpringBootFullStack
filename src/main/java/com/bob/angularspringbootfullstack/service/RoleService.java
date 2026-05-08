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
    Collection<Role> getAllRoles();
}
