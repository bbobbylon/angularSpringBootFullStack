package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.User;
import jakarta.validation.constraints.NotEmpty;

/**
 * UserService defines the business logic contract for user-related operations.
 * <p>
 * This service layer interface acts as a facade between the controller layer
 * and repository layer. It defines the operations available for user management,
 * ensuring consistent business logic and allowing for easy testing via mocking.
 * <p>
 * The service returns UserDTO objects instead of User entities to prevent
 * sensitive data (like passwords) from being exposed to the API clients.
 */
public interface UserService {
    /**
     * Creates a new user in the system with the provided credentials.
     *
     * @param user the user entity containing registration information
     * @return a UserDTO representing the created user (without password)
     */
    UserDTO createUser(User user);

    /**
     * Retrieves a user by their email address.
     *
     * @param email the email address to search for (must not be empty)
     * @return a UserDTO if user is found
     */
    UserDTO getUserByEmail(@NotEmpty String email);

    /**
     * Sends a 2FA verification code to the user via their registered contact method.
     *
     * @param userDTO the user who will receive the verification code
     */
    void sendVerificationCode(UserDTO userDTO);

    /**
     * Verifies a 2FA code for a user.
     *
     * @param email user's email
     * @param code  verification code supplied by the user
     * @return the verified user as a DTO
     */
    UserDTO verifyCode(String email, String code);

    /**
     * Starts the password reset flow for the given email address.
     *
     * <p>The implementation generates a one-time verification URL/key and persists it with an
     * expiration time.
     *
     * @param email email address of the user requesting a password reset
     */
    void resetPassword(String email);

    /**
     * Verifies a password reset key/link and returns the associated user.
     *
     * @param key password reset key from the verification URL
     * @return the user associated with the reset key
     */
    UserDTO verifyPasswordKey(String key);

    /**
     * Sets a new password using a valid password reset key.
     *
     * @param key             password reset key from the verification URL
     * @param newPassword     new password
     * @param confirmPassword must match {@code newPassword}
     */
    void setNewPassword(String key, String newPassword, String confirmPassword);

    /**
     * Verifies an account verification key and enables the user account.
     *
     * @param key account verification key from the verification URL
     * @return the verified user
     */
    UserDTO verifyAccount(String key);
}
