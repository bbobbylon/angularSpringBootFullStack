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

    UserDTO verifyCode(String email, String code);
}
