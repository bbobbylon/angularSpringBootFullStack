package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Role;

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
}
