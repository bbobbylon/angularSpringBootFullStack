package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.regex.Pattern;

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

    /**
     * Naming convention every role catalog row must follow (Role CRUD — create), matching the
     * shape every seeded {@link RoleType} constant already has: {@code ROLE_} followed by one or
     * more uppercase letters/underscores. Enforced here, not at the database, since it is a
     * business rule about what a valid role name looks like, not a storage constraint.
     */
    private static final Pattern ROLE_NAME_PATTERN = Pattern.compile("^ROLE_[A-Z_]+$");

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

    /**
     * Returns every role defined in the system by delegating to the repository.
     * <p>
     * Called by {@code GET /user/profile} to embed the full role catalog in the
     * response, giving the frontend everything it needs to populate the role
     * selector in the Authorization tab without a separate network request.
     *
     * @return a collection of all available {@link Role} entities
     */
    @Override
    public Collection<Role> getAllRoles() {
        Collection<Role> roles = roleRepository.list();
        // Stamped here, not in the repo: "is this name recognized" is RoleType's business rule
        // (canAssign already fails closed on it), so the catalog's read path just reflects it.
        roles.forEach(role -> role.setAssignable(RoleType.from(role.getName()).isPresent()));
        return roles;
    }

    /**
     * Validates the name/permission shape, normalizes the name to uppercase, and delegates to
     * the repository. See {@link #ROLE_NAME_PATTERN} for the accepted name shape.
     *
     * @param role the role to create
     * @return the created role with its generated id populated
     * @throws ApiException if the name is malformed/blank or the permission string is blank
     */
    @Override
    public Role createRole(Role role) {
        String name = role.getName() == null ? "" : role.getName().trim().toUpperCase();
        if (!ROLE_NAME_PATTERN.matcher(name).matches()) {
            throw new ApiException("Role name must look like ROLE_SOMETHING (uppercase letters and underscores only).");
        }
        if (role.getPermission() == null || role.getPermission().isBlank()) {
            throw new ApiException("Permission string is required.");
        }
        role.setName(name);
        return roleRepository.create(role);
    }

    /**
     * Validates the permission string is non-blank and delegates to the repository.
     *
     * @param id         the id of the role to update
     * @param permission the new comma-delimited permission string
     * @return the updated role
     * @throws ApiException if the permission string is blank
     */
    @Override
    public Role updateRolePermission(Long id, String permission) {
        if (permission == null || permission.isBlank()) {
            throw new ApiException("Permission string is required.");
        }
        Role patch = new Role();
        patch.setPermission(permission);
        return roleRepository.update(id, patch);
    }

    /**
     * Refuses to delete any of the seven built-in {@link RoleType} roles, then delegates to the
     * repository (which enforces the {@code ON DELETE RESTRICT} guard against a role still
     * assigned to a user).
     *
     * @param id the id of the role to delete
     * @throws ApiException if no role has that id, or it is a built-in role
     */
    @Override
    public void deleteRole(Long id) {
        Role role = roleRepository.get(id);
        if (RoleType.from(role.getName()).isPresent()) {
            throw new ApiException("'" + role.getName() + "' is a built-in role required by the application and cannot be deleted.");
        }
        roleRepository.delete(id);
    }
}

