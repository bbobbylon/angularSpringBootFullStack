package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Role;

import java.util.Collection;

/**
 * RoleRepo defines the data access contract for Role entities.
 *
 * This generic repository interface extends to any type T that extends Role,
 * providing a flexible CRUD (Create, Read, Update, Delete) contract plus
 * custom role management operations. Implementations handle direct database access.
 *
 * Generic CRUD operations provide standard database operations,
 * while custom methods handle role-specific queries and user-role relationships.
 *
 * @param <T> the type parameter representing Role or Role subtypes
 */
public interface RoleRepo<T extends Role> {
    /**
     * Creates a new role in the database.
     *
     * @param data the role entity to create
     * @return the created role with ID populated
     */
    T create(T data);

    /**
     * Retrieves a paginated list of roles.
     * Supports pagination for large datasets.
     *
     * @param page the page number (0-indexed)
     * @param pageSize the number of roles per page
     * @return a collection of roles on the specified page
     */
    Collection<T> list(int page, int pageSize);

    /**
     * Retrieves a single role by ID.
     *
     * @param id the role's unique identifier
     * @return the role if found, null otherwise
     */
    T get(Long id);

    /**
     * Updates an existing role in the database.
     *
     * @param id the ID of the role to update
     * @param data the updated role data
     * @return the updated role
     */
    T update(Long id, T data);

    /**
     * Deletes a role from the database.
     *
     * @param id the ID of the role to delete
     */
    void delete(Long id);

    /**
     * Assigns a role to a user by role name.
     * Creates a relationship in the user_roles junction table.
     *
     * @param userId the ID of the user to assign the role to
     * @param roleName the name of the role to assign
     * @throws ApiException if the role name is not found
     */
    void addRoleToUser(Long userId, String roleName);

    /**
     * Retrieves the role assigned to a specific user.
     *
     * @param userId the ID of the user
     * @return the role assigned to the user
     * @throws ApiException if user has no role assigned
     */
    Role getRoleByUserId(Long userId);

    /**
     * Retrieves a user's role by their email address.
     *
     * @param email the user's email address
     * @return the role assigned to the user with the specified email
     */
    Role getRoleByUserEmail(String email);

    /**
     * Updates a user's role assignment.
     *
     * @param userId the ID of the user whose role should be updated
     * @param roleName the new role name to assign
     */
    void updateUserRole(Long userId, String roleName);

}
