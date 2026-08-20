package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Role;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * RoleRepo defines the data access contract for Role entities.
 * <p>
 * This generic repository interface extends to any type T that extends Role,
 * providing a flexible CRUD (Create, Read, Update, Delete) contract plus
 * custom role management operations. Implementations handle direct database access.
 * <p>
 * Generic CRUD operations provide standard database operations,
 * while custom methods handle role-specific queries and user-role relationships.
 *
 * @param <T> the type parameter representing Role or Role subtypes
 */
public interface RoleRepo<T extends Role> {
    /**
     * Creates a new role catalog row (Role CRUD).
     *
     * <p>Roles created here have no {@link com.bob.angularspringbootfullstack.enumeration.RoleType}
     * constant until a redeploy adds one, so they exist in the catalog but cannot yet be
     * assigned to anyone — {@link com.bob.angularspringbootfullstack.enumeration.RoleType#canAssign}
     * fails closed on the unrecognized name. That is accepted, not a bug: see
     * {@code FUTURE-ENHANCEMENTS.md} §3.2's Role CRUD design notes.
     *
     * @param data the role entity to create (id is ignored/overwritten)
     * @return the created role with its generated ID populated
     */
    T create(T data);

    /**
     * Returns all roles stored in the database, ordered by ID.
     * <p>
     * The result is used by the {@code GET /user/profile} endpoint to embed the
     * full role catalog in the profile response so the frontend can populate
     * the Authorization tab role selector without issuing a separate request.
     *
     * @return a collection of all {@link Role} entities
     */
    Collection<T> list();

    /**
     * Retrieves a single role catalog row by its own ID (Role CRUD).
     *
     * @param id the role's unique identifier
     * @return the role
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no role has that id
     */
    T get(Long id);

    /**
     * Updates an existing role's permission string (Role CRUD — edit). The name is
     * immutable once created; see {@link #create} for why.
     *
     * @param id   the ID of the role to update
     * @param data the updated role data — only {@link Role#getPermission()} is applied
     * @return the updated role, freshly re-read from the database
     */
    T update(Long id, T data);

    /**
     * Deletes a role from the catalog (Role CRUD).
     *
     * @param id the ID of the role to delete
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no role has that id,
     *         or if any user currently holds it ({@code userroles.role_id} is
     *         {@code ON DELETE RESTRICT})
     */
    void delete(Long id);

    /**
     * Assigns a role to a user by role name.
     * Creates a relationship in the user_roles junction table.
     *
     * @param userId   the ID of the user to assign the role to
     * @param roleName the name of the role to assign
     *                 //@throws ApiException if the role name is not found
     */
    void addRoleToUser(Long userId, String roleName);

    /**
     * Retrieves the role assigned to a specific user.
     *
     * @param userId the ID of the user
     * @return the role assigned to the user
     * //@throws ApiException if the user has no role assigned
     */
    Role getRoleByUserId(Long userId);

    /**
     * Retrieves a user's role by their email address.
     *
     * @param email the user's email address
     * @return the role assigned to the user with the specified email
     */
    @SuppressWarnings("unused")
    Role getRoleByUserEmail(String email);

    /**
     * Updates a user's role assignment, optionally time-boxing it.
     *
     * @param userId    the ID of the user whose role should be updated
     * @param roleName  the new role name to assign
     * @param expiresAt when this assignment should expire, or {@code null} for unlimited (the
     *                  default). A non-null value is enforced live: the next time
     *                  {@link #getRoleByUserId} is called for this user after that instant, it
     *                  auto-reverts the assignment to {@code ROLE_USER} and clears this field —
     *                  there is no separate scheduled sweep job.
     */
    void updateUserRole(Long userId, String roleName, LocalDateTime expiresAt);

}
