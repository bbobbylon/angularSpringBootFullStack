package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * RoleServiceImpl provides role-related business operations.
 * <p>
 * This service acts as a bridge between the controller/business logic and the
 * RoleRepository, handling all role-related queries and operations.
 * <p>
 * Responsibilities:
 * - Retrieve roles by user ID
 * - Coordinate role data retrieval from database
 * - Apply any business logic related to roles
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
    private final RoleRepo<Role> roleRepository;

    /**
     * Retrieves the role assigned to a specific user.
     * +
     * This method queries the database to find the role associated with a user ID.
     * The role contains:
     * - Role ID
     * - Role name (e.g., "USER", "ADMIN")
     * - Permission string (e.g., "READ:USER,UPDATE:USER,DELETE:USER")
     * <p>
     * Used during authentication to:
     * - Extract user's permissions for JWT token creation
     * - Build UserPrincipal with authorities
     * - Enable authorization checks on protected endpoints
     *
     * @param id the user ID to look up the role for
     * @return Role object containing role name and permission string
     */
    @Override
    public Role getRoleByUserId(Long id) {
        return roleRepository.getRoleByUserId(id);
    }
}

