package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.User;

import java.util.Collection;

/**
 * UserRepo defines the data access contract for User entities.
 * <p>
 * This generic repository interface extends to any type T that extends User,
 * providing a flexible CRUD (Create, Read, Update, Delete) contract.
 * Implementations handle direct database access via SQL queries.
 * <p>
 * Generic CRUD operations include standard database operations,
 * while custom methods handle user-specific queries and operations.
 *
 * @param <T> the type parameter representing User or User subtypes
 */
public interface UserRepo<T extends User> {

    /**
     * Creates a new user in the database.
     *
     * @param data the user entity to create
     * @return the created user with ID populated
     */
    T create(T data);

    /**
     * Retrieves a paginated list of users.
     * Supports pagination for large datasets.
     *
     * @param page     the page number (0-indexed)
     * @param pageSize the number of users per page
     * @return a collection of users on the specified page
     */
    Collection<T> list(int page, int pageSize);

    /**
     * Retrieves a single user by ID.
     *
     * @param id the user's unique identifier
     * @return the user if found, null otherwise
     */
    T get(Long id);

    /**
     * Updates an existing user in the database.
     *
     * @param id   the ID of the user to update
     * @param data the updated user data
     * @return the updated user
     */
    T update(Long id, T data);

    /**
     * Deletes a user from the database.
     *
     * @param id the ID of the user to delete
     */
    void delete(Long id);

    /**
     * Retrieves a user by their email address.
     *
     * @param email the user's email address
     * @return the user if found
     * @throws ApiException if user is not found
     */
    User getUserByEmail(String email);

    /**
     * Sends a 2FA verification code to the specified user.
     *
     * @param userDTO the user who will receive the verification code
     */
    void sendVerificationCode(UserDTO userDTO);

    User verifyCode(String email, String code);

    void resetPassword(String email);

    T verifyPasswordKey(String key);

    void setNewPassword(String key, String newPassword, String confirmPassword);

    T verifyAccountKey(String key);
}
